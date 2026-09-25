package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.harness.context.AgentContext;
import org.skylark.langur.domain.harness.context.AgentContextRepository;
import org.skylark.langur.domain.harness.context.ContextAssembler;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.Checksums;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;
import org.skylark.langur.domain.harness.lifecycle.HookAction;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E 组件默认实现 - ReAct 执行循环（底层范式）。
 * <p>标准执行时序（对齐架构设计 3.4/8.1）：
 * [C] 上下文装配 → [E] 推理决策 → [T] 工具执行 → [S] 快照写入 → [L] 全节点拦截 → [V] 指标留存。</p>
 * <p>纯领域实现（无 Spring 依赖），由 start 层装配。</p>
 */
public class ReActExecutionLoop implements ExecutionLoopService {

    private final AgentDomainService agentDomainService;
    private final LifecycleHookEngine hookEngine;
    private final TaskStateRepository taskStateRepository;
    private final EvaluationService evaluationService;
    private final ContextAssembler contextAssembler;
    private final AgentContextRepository contextRepository;

    /** 循环检测器（可选装配，T7）；缺省不启用循环检测 */
    private LoopDetector loopDetector;

    public void attachLoopDetector(LoopDetector loopDetector) {
        this.loopDetector = loopDetector;
    }

    /** 全链路追踪器（可选装配，T12）；缺省使用 NOOP，不产生 Span（P10 降级兜底） */
    private ExecutionTracer tracer;

    public void attachTracer(ExecutionTracer tracer) {
        this.tracer = tracer;
    }

    private ExecutionSpan startSpan(SpanType type, String operationName) {
        return (tracer != null ? tracer : ExecutionTracer.NOOP).startSpan(type, operationName);
    }

    public ReActExecutionLoop(AgentDomainService agentDomainService,
                              LifecycleHookEngine hookEngine,
                              TaskStateRepository taskStateRepository,
                              EvaluationService evaluationService) {
        this(agentDomainService, hookEngine, taskStateRepository, evaluationService, null, null);
    }

    public ReActExecutionLoop(AgentDomainService agentDomainService,
                              LifecycleHookEngine hookEngine,
                              TaskStateRepository taskStateRepository,
                              EvaluationService evaluationService,
                              ContextAssembler contextAssembler,
                              AgentContextRepository contextRepository) {
        this.agentDomainService = agentDomainService;
        this.hookEngine = hookEngine;
        this.taskStateRepository = taskStateRepository;
        this.evaluationService = evaluationService;
        this.contextAssembler = contextAssembler;
        this.contextRepository = contextRepository;
    }

    @Override
    public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
        long startMillis = System.currentTimeMillis();
        int toolCalls = 0;
        int interceptions = 0;
        int realTokenRounds = 0;
        int estimatedTokenRounds = 0;
        task.start();

        // [S] 断点续跑 + 并发重入保护（T5）：优先复用既有任务状态并获取执行锁
        String lockHolder = task.getTraceId();
        TaskState state = taskStateRepository.findById(task.getTaskId()).orElse(null);
        if (state != null) {
            if (!state.acquireLock(lockHolder)) {
                task.terminate("Concurrent execution rejected: task is locked by another holder");
                return task;
            }
            if (state.isResumable()) {
                StateSnapshot latest = state.latestSnapshot();
                task.resumeFromRound(latest.getRound());
                agent.resumeIteration(readIteration(latest));
            }
        } else {
            state = TaskState.init(task.getTaskId());
            state.acquireLock(lockHolder);
        }
        state.transition(TaskStateStatus.RUNNING);

        // [L] 上下文装配阶段拦截
        if (abortIfRejected(HookPoint.BEFORE_CONTEXT_ASSEMBLE, task, agent.getConfig().getSystemPrompt())) {
            interceptions++;
            auditInterception(task, "CONTEXT_ASSEMBLY_REJECTED", "before_context_assemble aborted");
            return finishAborted(task, state, "Context assembly rejected by lifecycle hook");
        }
        // [C] 上下文装配：双画像 + 分层记忆 + 脱敏过滤 + Token 预算治理（§8.1）
        try (ExecutionSpan span = startSpan(SpanType.CONTEXT, "context.assemble")) {
            span.setAttribute("taskId", task.getTaskId());
            assembleContext(task, agent);
            span.setAttribute("messageCount", (long) agent.getConversationHistory().size());
        }
        fire(HookPoint.AFTER_CONTEXT_ASSEMBLE, task, agent.getConversationHistory().size() + " messages");

        // [E] 闸门 Token 维度：初始上下文尚无 LLM 调用，按字符数估算计入消耗（H1：后续每轮优先用真实 usage）
        task.addTokens(estimateTokens(agent));

        String finalAnswer = null;
        boolean loopDetected = false;
        try {
            while (finalAnswer == null && !agent.hasExceededMaxIterations() && !task.gateTripped()) {
                // [L] 推理前置拦截：安全策略可在此中断/改写（H10：载荷为最新用户输入，供注入检测扫描）
                if (abortIfRejected(HookPoint.BEFORE_INFERENCE, task, inferencePayload(agent))) {
                    interceptions++;
                    auditInterception(task, "INFERENCE_REJECTED", "before_inference aborted");
                    break;
                }

                task.nextRound();
                // [E] 驱动单步 ReAct（内部经 [T] 执行工具并触发 BEFORE/AFTER_TOOL_CALL 钩子）
                long tokensBefore = estimateTokens(agent);
                try (ExecutionSpan span = startSpan(SpanType.INFERENCE, "llm.reasoning")) {
                    span.setAttribute("round", (long) task.getCurrentRound());
                    span.setAttribute("model", agent.getConfig().getModel());
                    try {
                        finalAnswer = agentDomainService.executeReActStep(agent, plan);
                        span.setAttribute("finalAnswer", finalAnswer != null);
                    } catch (RuntimeException e) {
                        span.recordError(e);
                        throw e;
                    }
                }
                // [E] 闸门 Token 维度（H1）：优先用 LLM 回传的真实 usage，缺失时降级字符估算并标注来源
                LLMPort.TokenUsage usage = agent.consumeLastRoundUsage();
                if (usage != null && !usage.isEmpty()) {
                    task.addTokens(usage.getTotalTokens());
                    realTokenRounds++;
                } else {
                    long tokensAfter = estimateTokens(agent);
                    if (tokensAfter > tokensBefore) {
                        task.addTokens(tokensAfter - tokensBefore);
                        estimatedTokenRounds++;
                    }
                }
                // [E] 闸门单轮调用数维度：本轮产出工具调用则计数（未产出即给出最终答案）
                if (finalAnswer == null) {
                    task.recordToolCall();
                    toolCalls++;
                }

                fire(HookPoint.AFTER_INFERENCE, task,
                        finalAnswer != null ? "finalAnswer" : "toolCall:" + plan.getSteps().size());

                // [E] 循环检测（T7）：最近 N 轮指纹重复超阈值则强制终止
                if (finalAnswer == null && loopDetector != null && detectLoop(plan)) {
                    loopDetected = true;
                    interceptions++;
                    auditInterception(task, "LOOP_DETECTED", "loop detector tripped");
                    break;
                }

                // [S] 本轮快照写入（断点续跑/回滚基础）
                if (fire(HookPoint.BEFORE_STATE_SAVE, task, null) == null) {
                    try (ExecutionSpan span = startSpan(SpanType.SNAPSHOT, "state.save")) {
                        state.addSnapshot(StateSnapshot.of(task.getTaskId(), task.getCurrentRound(), snapshotPayload(agent, plan)));
                        taskStateRepository.save(state);
                        span.setAttribute("snapshotId", state.latestSnapshot().getSnapshotId());
                        span.setAttribute("round", (long) state.latestSnapshot().getRound());
                    }
                    fire(HookPoint.AFTER_STATE_SAVE, task, state.latestSnapshot().getSnapshotId());
                }
            }

            if (finalAnswer != null) {
                // [L] BEFORE_OUTPUT 输出合规拦截（T4）：可 MODIFY 改写 / ABORT 阻断
                String compliantAnswer;
                try (ExecutionSpan span = startSpan(SpanType.OUTPUT, "output.compliance")) {
                    compliantAnswer = applyBeforeOutput(task, agent, finalAnswer);
                    span.setAttribute("aborted", compliantAnswer == null);
                    if (compliantAnswer != null) {
                        span.setAttribute("answerLength", (long) compliantAnswer.length());
                    }
                }
                if (compliantAnswer == null) {
                    interceptions++;
                    auditInterception(task, "OUTPUT_REJECTED", "before_output aborted");
                    task.terminate("Output rejected by lifecycle hook");
                    state.transition(TaskStateStatus.FAILED);
                } else {
                    finalAnswer = compliantAnswer;
                    task.complete();
                    state.transition(TaskStateStatus.COMPLETED);
                    // [L] AFTER_OUTPUT：最终答案产出后触发
                    fire(HookPoint.AFTER_OUTPUT, task, finalAnswer);
                }
            } else {
                // 终止原因归因：循环检测 / 闸门触发 优先于迭代上限（保护性终止是第一性保护）
                String reason;
                if (loopDetected) {
                    reason = "LOOP_DETECTED";
                } else if (task.gateTripped()) {
                    reason = "Termination gate tripped";
                } else {
                    reason = "Max iterations exceeded";
                }
                task.terminate(reason);
                state.transition(TaskStateStatus.FAILED);
            }
        } catch (RuntimeException e) {
            task.fail(e.getMessage());
            state.transition(TaskStateStatus.FAILED);
        }

        // [L] 终止阶段拦截
        fire(HookPoint.BEFORE_TERMINATE, task, task.getTerminateReason());
        fire(HookPoint.AFTER_TERMINATE, task, String.valueOf(task.getStatus()));

        state.releaseLock();
        taskStateRepository.save(state);

        // [V] 四维指标上报（失败静默降级，不影响主链路）
        reportMetrics(task, startMillis, toolCalls, interceptions, realTokenRounds, estimatedTokenRounds);
        return task;
    }

    /**
     * [L] BEFORE_OUTPUT 输出合规拦截（T4）：MODIFY 改写最终答案并回写 Agent，ABORT 阻断返回。
     *
     * @return 合规后的答案；返回 {@code null} 表示被 ABORT 阻断
     */
    private String applyBeforeOutput(ExecutionTask task, Agent agent, String finalAnswer) {
        if (hookEngine == null) {
            return finalAnswer;
        }
        HookContext context = HookContext.of(HookPoint.BEFORE_OUTPUT, task.getTraceId(), finalAnswer);
        HookResult result = hookEngine.fire(context);
        if (result != null && result.getAction() == HookAction.ABORT) {
            return null;
        }
        String compliant = context.getPayload() != null ? context.getPayload() : finalAnswer;
        if (!compliant.equals(finalAnswer)) {
            agent.overrideFinalAnswer(compliant);
        }
        return compliant;
    }

    /**
     * [E] 循环检测（T7）：以最近一步的 action/observation 作为指纹喂入检测器。
     */
    private boolean detectLoop(Plan plan) {
        if (plan.getSteps().isEmpty()) {
            return false;
        }
        PlanStep last = plan.getSteps().get(plan.getSteps().size() - 1);
        return loopDetector.recordAndDetect(last.getAction(), last.getObservation());
    }

    /**
     * [L] BEFORE_INFERENCE 载荷（H10）：取最新一条 user 消息供注入检测扫描，
     * 无用户消息时降级为系统提示词。
     */
    private String inferencePayload(Agent agent) {
        List<Map<String, String>> history = agent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> message = history.get(i);
            if ("user".equalsIgnoreCase(message.get("role"))) {
                return message.get("content");
            }
        }
        return agent.getConfig().getSystemPrompt();
    }

    private int readIteration(StateSnapshot snapshot) {
        Object iteration = snapshot.getPayload() != null ? snapshot.getPayload().get("iteration") : null;
        return iteration instanceof Number ? ((Number) iteration).intValue() : 0;
    }

    private ExecutionTask finishAborted(ExecutionTask task, TaskState state, String reason) {
        task.terminate(reason);
        state.transition(TaskStateStatus.FAILED);
        taskStateRepository.save(state);
        return task;
    }

    /**
     * [C] 执行上下文装配并持久化（装配器/仓储未装配时静默跳过，不影响主链路）
     */
    private void assembleContext(ExecutionTask task, Agent agent) {
        if (contextAssembler == null) {
            return;
        }
        AgentContext context = contextAssembler.assemble(
                task.getTraceId(), task.getBizCode(), agent, task.getGate().getMaxTokens());
        if (contextRepository != null) {
            contextRepository.save(context);
        }
    }

    /**
     * Token 估算（约 4 字符/Token）：LLM 接入真实 usage 返回前的兜底计量（D3 决策）
     */
    private long estimateTokens(Agent agent) {
        return agent.getConversationHistory().stream()
                .mapToLong(message -> String.valueOf(message.get("content")).length() / 4L + 1L)
                .sum();
    }

    /**
     * 触发拦截点；返回非 null 表示链路被 ABORT
     */
    private HookResult fire(HookPoint point, ExecutionTask task, String payload) {
        if (hookEngine == null) {
            return null;
        }
        HookResult result = hookEngine.fire(HookContext.of(point, task.getTraceId(), payload));
        return result != null && result.getAction() == HookAction.ABORT ? result : null;
    }

    private boolean abortIfRejected(HookPoint point, ExecutionTask task, String payload) {
        return fire(point, task, payload) != null;
    }

    private Map<String, Object> snapshotPayload(Agent agent, Plan plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentStatus", agent.getStatus().name());
        payload.put("iteration", agent.getIterationCount());
        payload.put("planSteps", plan.getSteps().size());
        return payload;
    }

    private void reportMetrics(ExecutionTask task, long startMillis, int toolCalls, int interceptions,
                               int realTokenRounds, int estimatedTokenRounds) {
        if (evaluationService == null) {
            return;
        }
        try {
            ExecutionMetrics metrics = ExecutionMetrics.of(task.getTraceId());
            // 调度层
            metrics.record(MetricDimension.SCHEDULING, "totalRounds", task.getCurrentRound());
            metrics.record(MetricDimension.SCHEDULING, "paradigm", task.getParadigm().name());
            metrics.record(MetricDimension.SCHEDULING, "terminateReason",
                    task.getTerminateReason() != null ? task.getTerminateReason() : "COMPLETED");
            metrics.record(MetricDimension.SCHEDULING, "elapsedMillis", System.currentTimeMillis() - startMillis);
            // 模型层
            metrics.record(MetricDimension.MODEL, "consumedTokens", task.getConsumedTokens());
            // 模型层（H1）：Token 计量来源，real=provider usage，estimated=字符估算兜底
            metrics.record(MetricDimension.MODEL, "realTokenRounds", realTokenRounds);
            metrics.record(MetricDimension.MODEL, "estimatedTokenRounds", estimatedTokenRounds);
            // 工具层
            metrics.record(MetricDimension.TOOL, "toolCalls", toolCalls);
            // 安全层
            metrics.record(MetricDimension.SECURITY, "interceptions", interceptions);
            evaluationService.report(metrics);
        } catch (RuntimeException ignored) {
            // P10：埋点/监控失败静默丢弃，不影响主链路
        }
    }

    /**
     * [V] 不可篡改审计（T12）：拦截/阻断事件附 SHA-256 checksum 落库，支撑溯源与防篡改校验。
     * <p>审计失败静默降级，不影响主链路（P10）。</p>
     */
    private void auditInterception(ExecutionTask task, String action, String detail) {
        if (evaluationService == null) {
            return;
        }
        try {
            String actor = task.getBizCode() != null ? task.getBizCode() : "system";
            String checksum = Checksums.sha256(task.getTraceId(), actor, action, detail);
            evaluationService.audit(AuditRecord.of(task.getTraceId(), actor, action, detail, checksum));
        } catch (RuntimeException ignored) {
            // P10：审计上报失败静默丢弃，不影响主链路
        }
    }
}
