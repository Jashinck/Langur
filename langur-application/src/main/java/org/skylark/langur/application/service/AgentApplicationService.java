package org.skylark.langur.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.application.assembler.AgentAssembler;
import org.skylark.langur.application.command.CreateAgentCommand;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.dto.PlanResult;
import org.skylark.langur.application.dto.PlanStepResult;
import org.skylark.langur.common.exception.AgentNotFoundException;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.agent.AgentId;
import org.skylark.langur.domain.model.message.MessagePartType;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.repository.AgentRepository;
import org.skylark.langur.domain.repository.PlanRepository;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.domain.service.PlanningDomainService;
import org.springframework.stereotype.Service;

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

    private final AgentRepository agentRepository;
    private final AgentDomainService agentDomainService;
    private final PlanningDomainService planningDomainService;
    private final ToolRegistryService toolRegistryService;
    private final AgentAssembler agentAssembler;
    private final PlanRepository planRepository;

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
        Agent agent = findAgent(command.getAgentId());

        agent.markRunning();
        String resolvedUserMessage = resolveUserMessage(command);
        agent.addUserMessage(resolvedUserMessage);
        Plan plan = planningDomainService.createPlan(agent.getId().getValue());

        try {
            String finalAnswer = null;
            while (finalAnswer == null && !agent.hasExceededMaxIterations()) {
                finalAnswer = agentDomainService.executeReActStep(agent, plan);
            }
            if (finalAnswer == null) {
                agent.markFailed("Max iterations exceeded");
            }
        } catch (Exception e) {
            log.error("Agent execution failed", e);
            agent.markFailed(e.getMessage());
        } finally {
            planRepository.save(plan);
        }

        agentRepository.save(agent);
        return agentAssembler.toResult(agent);
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
