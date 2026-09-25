package org.skylark.langur.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.application.assembler.AgentAssembler;
import org.skylark.langur.application.command.CreateAgentCommand;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.dto.ArtifactResult;
import org.skylark.langur.application.dto.PlanResult;
import org.skylark.langur.application.dto.PlanStepResult;
import org.skylark.langur.application.stream.StreamEventHandler;
import org.skylark.langur.common.exception.AgentNotFoundException;
import org.skylark.langur.common.spi.BizContext;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;
import org.skylark.langur.domain.harness.execution.ExecutionLoopService;
import org.skylark.langur.domain.harness.execution.ExecutionTask;
import org.skylark.langur.domain.harness.execution.LayerRouter;
import org.skylark.langur.domain.harness.execution.RuntimeParadigm;
import org.skylark.langur.domain.harness.execution.TerminationGate;
import org.skylark.langur.domain.harness.spi.BizCodeRouter;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.agent.AgentId;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.model.message.MessagePartType;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.repository.AgentRepository;
import org.skylark.langur.domain.repository.PlanRepository;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.domain.service.PlanningDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Agent应用服务 - 编排领域对象，处理用例
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApplicationService {

    private static final String DEFAULT_BIZ_CODE = "default";

    private final AgentRepository agentRepository;
    private final AgentDomainService agentDomainService;
    private final PlanningDomainService planningDomainService;
    private final ToolRegistryService toolRegistryService;
    private final AgentAssembler agentAssembler;
    private final PlanRepository planRepository;
    private final ExecutionLoopService executionLoopService;
    private final LayerRouter layerRouter;
    private final BizCodeRouter bizCodeRouter;

    /** 全链路追踪器（T12）；可选注入，缺省 NOOP，产生 API 接入 Span。 */
    private ExecutionTracer executionTracer = ExecutionTracer.NOOP;

    @Autowired(required = false)
    public void setExecutionTracer(ExecutionTracer executionTracer) {
        if (executionTracer != null) {
            this.executionTracer = executionTracer;
        }
    }

    public AgentResult createAgent(CreateAgentCommand command) {
        AgentConfig config = AgentConfig.builder()
                .name(command.getName())
                .description(command.getDescription())
                .systemPrompt(command.getSystemPrompt() != null
                        ? command.getSystemPrompt()
                        : "You are a helpful assistant.")
                .model(command.getModel() != null ? command.getModel() : "gpt-4o")
                .temperature(command.getTemperature() > 0 ? command.getTemperature() : 0.7)
                .maxIterations(command.getMaxIterations() > 0 ? command.getMaxIterations() : 10)
                .maxTokens(4096)
                .build();

        Agent agent = Agent.create(config);
        registerConfiguredTools(agent, command.getToolNames());

        agentRepository.save(agent);
        log.info("Created agent: {} ({})", agent.getConfig().getName(), agent.getId());
        return agentAssembler.toResult(agent);
    }

    public AgentResult runAgent(RunAgentCommand command) {
        // [V] API 接入 Span（T12，§10.2）：包裹整个同步执行链路，traceId 由此贯穿
        try (ExecutionSpan span = executionTracer.startSpan(SpanType.API, "agent.run")) {
            span.setAttribute("agentId", command.getAgentId());
            try {
                return doRunAgent(command);
            } catch (RuntimeException e) {
                span.recordError(e);
                throw e;
            }
        }
    }

    private AgentResult doRunAgent(RunAgentCommand command) {
        Agent agent = findAgent(command.getAgentId());

        agent.markRunning();
        String resolvedUserMessage = resolveUserMessage(command);
        agent.addUserMessage(resolvedUserMessage);
        Plan plan = planningDomainService.createPlan(agent.getId().getValue());

        ExecutionTask task = null;
        try {
            // [E] 范式路由（T6/T9）：bizCode 经 SPI 上下文增强 + 决策引擎提示，再交 LayerRouter 决策
            String bizCode = resolveBizCode(command);
            BizContext bizContext = buildBizContext(agent, bizCode, resolvedUserMessage, command);
            bizCodeRouter.contextEnrichers().forEach(enricher -> enricher.enrich(bizContext));
            RuntimeParadigm paradigm = resolveEffectiveParadigm(
                    layerRouter.paradigmOf(layerRouter.route(bizCode, resolvedUserMessage)));
            // [E] 经 Harness 执行循环驱动：终止闸门 + 生命周期钩子 + 状态快照 + 指标观测
            task = ExecutionTask.create(
                    agent.getId().getValue(),
                    bizCode,
                    paradigm,
                    buildGate(agent.getConfig()));
            executionLoopService.execute(task, agent, plan);
            if (agent.getStatus() == AgentStatus.RUNNING) {
                agent.markFailed(task.getTerminateReason() != null
                        ? task.getTerminateReason()
                        : "Max iterations exceeded");
            }
        } catch (Exception e) {
            log.error("Agent execution failed", e);
            agent.markFailed(e.getMessage());
        } finally {
            planRepository.save(plan);
        }

        agentRepository.save(agent);
        AgentResult result = agentAssembler.toResult(agent);
        if (task != null && !task.getArtifacts().isEmpty()) {
            result.setArtifacts(task.getArtifacts().stream()
                    .map(artifact -> ArtifactResult.builder()
                            .name(artifact.getName())
                            .type(artifact.getType())
                            .content(artifact.getContent())
                            .build())
                    .toList());
        }
        return result;
    }

    private TerminationGate buildGate(AgentConfig config) {
        return TerminationGate.builder()
                .maxRounds(config.getMaxIterations())
                .maxTokens(config.getMaxTokens() > 0 ? (long) config.getMaxTokens() : 32_000L)
                .maxTimeout(Duration.ofMinutes(5))
                .maxCallsPerRound(5)
                .build();
    }

    /**
     * [E] 范式路由降级（T6/H3/H9）：ReAct / PlanAndExecute / Workflow / Hybrid 引擎均已落地，直接透传；
     * 其余（未来新增范式）显式降级为 ReAct 并记录。
     */
    private RuntimeParadigm resolveEffectiveParadigm(RuntimeParadigm routed) {
        if (routed == RuntimeParadigm.REACT || routed == RuntimeParadigm.PLAN_AND_EXECUTE
                || routed == RuntimeParadigm.WORKFLOW || routed == RuntimeParadigm.HYBRID) {
            return routed;
        }
        log.warn("Runtime paradigm [{}] engine not implemented yet, degrade to REACT", routed);
        return RuntimeParadigm.REACT;
    }

    /**
     * [E] bizCode 解析（T9）：命令未携带时回退 default 业务域。
     */
    private String resolveBizCode(RunAgentCommand command) {
        return StringUtils.isNotBlank(command.getBizCode()) ? command.getBizCode() : DEFAULT_BIZ_CODE;
    }

    /**
     * [E] 构建 SPI 业务上下文（T9）：供上下文增强链与后续 SPI 路由使用。
     */
    private BizContext buildBizContext(Agent agent, String bizCode, String userMessage, RunAgentCommand command) {
        return BizContext.builder()
                .bizCode(bizCode)
                .agentId(agent.getId().getValue())
                .userId(command.getUserId())
                .tenantId(command.getTenantId())
                .sessionId(command.getSessionId())
                .userMessage(userMessage)
                .build();
    }

    /**
     * 流式对话（§13.1 / T8）：message（逐块）→ summary → done。
     * <p>复用同步入口的 Agent 加载与消息解析；逐块回调由调用方（SSE）承接。</p>
     */
    public void streamChat(RunAgentCommand command, StreamEventHandler handler) {
        try {
            Agent agent = findAgent(command.getAgentId());
            agent.markRunning();
            agent.addUserMessage(resolveUserMessage(command));
            StringBuilder answer = new StringBuilder();
            agentDomainService.streamFinalAnswer(agent, token -> {
                answer.append(token);
                handler.send("message", token);
            });
            agentRepository.save(agent);
            handler.send("summary", "status=" + agent.getStatus().name() + ", length=" + answer.length());
            handler.send("done", agent.getId().getValue());
            handler.complete();
        } catch (Exception e) {
            log.error("Stream chat failed", e);
            handler.fail(e);
        }
    }

    public Optional<PlanResult> getLatestPlan(String agentId) {
        return planRepository.findByAgentId(agentId).map(this::toPlanResult);
    }

    public AgentResult getAgent(String agentId) {
        return agentAssembler.toResult(findAgent(agentId));
    }

    public List<AgentResult> listAgents() {
        return agentRepository.findAll().stream()
                .map(this::rehydrateTools)
                .map(agentAssembler::toResult)
                .toList();
    }

    public void deleteAgent(String agentId) {
        agentRepository.delete(AgentId.of(agentId));
        planRepository.delete(agentId);
    }

    private PlanResult toPlanResult(Plan plan) {
        List<PlanStepResult> steps = plan.getSteps().stream()
                .map(step -> PlanStepResult.builder()
                        .index(step.getIndex())
                        .thought(step.getThought())
                        .action(step.getAction())
                        .actionInput(step.getActionInput())
                        .observation(step.getObservation())
                        .status(step.getStatus().name())
                        .build())
                .toList();
        return PlanResult.builder()
                .agentId(plan.getAgentId())
                .currentStepIndex(plan.getCurrentStepIndex())
                .steps(steps)
                .build();
    }

    private Agent findAgent(String agentId) {
        Agent agent = agentRepository.findById(AgentId.of(agentId))
                .orElseThrow(() -> new AgentNotFoundException(agentId));
        return rehydrateTools(agent);
    }

    private Agent rehydrateTools(Agent agent) {
        List<String> registeredToolNames = agent.getRegisteredToolNames();
        if (registeredToolNames.isEmpty()) {
            return agent;
        }
        List<String> missingToolNames = registeredToolNames.stream()
                .filter(name -> agent.getTools().stream().noneMatch(tool -> tool.getName().equals(name)))
                .toList();
        if (missingToolNames.isEmpty()) {
            return agent;
        }
        toolRegistryService.getToolsByNames(missingToolNames).stream()
                .forEach(agent::registerTool);
        return agent;
    }

    private void registerConfiguredTools(Agent agent, List<String> toolNames) {
        toolRegistryService.getToolsByNames(toolNames).forEach(agent::registerTool);
    }

    private String resolveUserMessage(RunAgentCommand command) {
        if (StringUtils.isNotBlank(command.getUserMessage())) {
            return command.getUserMessage();
        }

        List<MessagePartInput> parts = command.getMessageParts();
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("userMessage or messageParts must be provided");
        }

        String merged = parts.stream()
                .map(this::toTextLine)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n"));

        if (StringUtils.isBlank(merged)) {
            throw new IllegalArgumentException("messageParts did not produce any usable content");
        }
        return merged;
    }

    private String toTextLine(MessagePartInput part) {
        MessagePartType type = part.getType() != null ? part.getType() : MessagePartType.TEXT;
        if (type == MessagePartType.TEXT) {
            return StringUtils.defaultString(part.getContent());
        }

        String payload = StringUtils.defaultIfBlank(part.getContent(), part.getMediaUrl());
        if (StringUtils.isBlank(payload)) {
            payload = "empty";
        }
        return "[" + type.name().toLowerCase() + "] " + payload;
    }
}
