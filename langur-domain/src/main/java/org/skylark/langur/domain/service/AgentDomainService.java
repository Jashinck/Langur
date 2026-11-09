package org.skylark.langur.domain.service;

import lombok.RequiredArgsConstructor;
import org.skylark.langur.domain.harness.execution.RetryPolicy;
import org.skylark.langur.domain.harness.context.ContextBuilder;
import org.skylark.langur.domain.harness.lifecycle.HookAction;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.message.SessionEvent;
import org.skylark.langur.domain.model.message.ToolCall;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.FallbackStrategy;
import org.skylark.langur.domain.port.LLMPort;

import java.util.List;
import java.util.function.Consumer;

/**
 * Agent领域服务 - 编排ReAct（推理-行动）循环。
 * <p>纯领域服务（无 Spring 注解），由 start 层通过 @Configuration 装配，保证 Domain 层零外部依赖（P1）。</p>
 */
@RequiredArgsConstructor
public class AgentDomainService {

    private final LLMPort llmPort;

    /** L 组件钩子引擎（可选装配，缺省时工具调用不经过拦截链） */
    private LifecycleHookEngine hookEngine;

    /** T 组件统一调度器（可选装配；装配后工具调用必经四层校验链 + 沙箱，§1.2 强制隔离） */
    private ToolDispatcher toolDispatcher;

    /** 容错重试策略（可选装配；缺省不重试，保持既有单次执行语义，T7） */
    private RetryPolicy retryPolicy;

    /** 降级策略（可选装配；LLM/工具失败时的兜底话术，T7） */
    private FallbackStrategy fallbackStrategy;

    /** C 组件上下文构建器（H15.2，可选装配；缺失时 decide 直用原始事件日志，P10 行为不变） */
    private ContextBuilder contextBuilder;

    public void attachHookEngine(LifecycleHookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    public void attachToolDispatcher(ToolDispatcher toolDispatcher) {
        this.toolDispatcher = toolDispatcher;
    }

    /**
     * 织入上下文构建器（H15.2）：推理前把会话事件裁剪进预算（规则 elision + head/tail 截断）。
     */
    public void attachContextBuilder(ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    /**
     * 织入容错能力（T7）：工具重试策略 + LLM/工具降级策略。
     */
    public void attachResilience(RetryPolicy retryPolicy, FallbackStrategy fallbackStrategy) {
        this.retryPolicy = retryPolicy;
        this.fallbackStrategy = fallbackStrategy;
    }

    /**
     * 执行单次ReAct迭代：思考 -> 选择工具 -> 执行工具
     * @return 最终答案（如果本轮完成），或null（继续迭代）
     */
    public String executeReActStep(Agent agent, Plan plan) {
        if (agent.hasExceededMaxIterations()) {
            return "Max iterations reached. Last known state: " + plan.toTraceString();
        }

        agent.incrementIteration();

        LLMPort.LLMDecision decision;
        try {
            List<SessionEvent> promptEvents = contextBuilder != null
                    ? contextBuilder.build(agent.getSessionEvents())
                    : agent.getSessionEvents();
            decision = llmPort.decide(
                    agent.getConfig().getSystemPrompt(),
                    agent.getConfig().getModel(),
                    promptEvents,
                    agent.getTools()
            );
        } catch (RuntimeException e) {
            // [E] LLM 超时/异常兜底（§8.2）：降级话术作为最终答案，避免主链路中断
            String fallback = fallbackStrategy != null
                    ? fallbackStrategy.onLlmFailure(agent.getConfig().getModel(), lastUserMessage(agent), e)
                    : "LLM invocation failed: " + e.getMessage();
            agent.markCompleted(fallback);
            return fallback;
        }

        // [V] H1：回填本轮真实 Token 计量，供执行循环计量与闸门消费（缺失时循环降级估算）
        agent.recordTokenUsage(decision.getUsage());

        if (decision.isFinalAnswer()) {
            agent.markCompleted(decision.getFinalAnswer());
            return decision.getFinalAnswer();
        }

        List<ToolCall> toolCalls = decision.getToolCalls();
        if (toolCalls.isEmpty()) {
            // 模型既未给出最终答案也无工具调用：按空回答终止，避免死循环
            agent.markCompleted("");
            return "";
        }

        // H15.1：单条 assistant 事件携带全部原生 tool_calls，后续逐条 tool 结果回指 toolCallId
        agent.addAssistantToolCalls(decision.getThought(), toolCalls);

        for (ToolCall call : toolCalls) {
            PlanStep step = PlanStep.builder()
                    .index(agent.getIterationCount())
                    .thought(decision.getThought())
                    .action(call.name())
                    .actionInput(call.arguments())
                    .status(StepStatus.RUNNING)
                    .build();
            plan.addStep(step);

            Tool tool = agent.getTools().stream()
                    .filter(t -> t.getName().equals(call.name()))
                    .findFirst()
                    .orElse(null);

            ToolResult result;
            if (tool == null) {
                result = ToolResult.failure("Tool not found: " + call.name());
            } else if (isToolCallBlocked(agent, call)) {
                result = ToolResult.failure("Tool call rejected by lifecycle hook: " + call.name());
            } else {
                // [E] 工具执行 + 容错重试（§8.2 重试→降级）
                result = executeWithRetry(agent, tool, call);
            }
            fireToolHook(HookPoint.AFTER_TOOL_CALL, agent, call.name(), result.getEffectiveContent());

            int lastIndex = plan.getSteps().size() - 1;
            plan.updateStep(lastIndex, result.isSuccess()
                    ? step.withObservation(result.getContent())
                    : step.withFailure(result.getError()));

            agent.addToolResultMessage(call.id(), call.name(), result.getEffectiveContent());
        }

        return null;
    }

    /**
     * [L] BEFORE_TOOL_CALL 拦截：安全策略可在工具执行前阻断调用（四层纵深防御之执行安全）
     */
    private boolean isToolCallBlocked(Agent agent, ToolCall call) {
        return fireToolHook(HookPoint.BEFORE_TOOL_CALL, agent, call.name(),
                String.valueOf(call.arguments())) != null;
    }

    /**
     * [E] 工具执行 + 容错重试（T7）：失败按策略退避重试，重试耗尽后走降级话术。
     */
    private ToolResult executeWithRetry(Agent agent, Tool tool, ToolCall call) {
        RetryPolicy policy = retryPolicy != null ? retryPolicy : RetryPolicy.noRetry();
        ToolResult result = null;
        int attempt = 0;
        while (true) {
            attempt++;
            result = executeOnce(agent, tool, call);
            if (result.isSuccess() || !policy.shouldRetry(attempt)) {
                break;
            }
            policy.backoff(attempt);
        }
        if (!result.isSuccess() && fallbackStrategy != null) {
            return ToolResult.failure(fallbackStrategy.onToolFailure(call.name(), result.getError()));
        }
        return result;
    }

    private ToolResult executeOnce(Agent agent, Tool tool, ToolCall call) {
        if (toolDispatcher != null) {
            // [T] 统一调度入口：白名单 → Schema → 动态权限 → 沙箱执行（§1.2 强制隔离）
            return dispatchThroughToolCenter(agent, call);
        }
        try {
            return tool.execute(call.arguments());
        } catch (Exception e) {
            return ToolResult.failure("Tool execution error: " + e.getMessage());
        }
    }

    /**
     * 流式产出最终答案（T8）：逐块回调 token，累积完成后落库为已完成答案。
     */
    public String streamFinalAnswer(Agent agent, Consumer<String> tokenConsumer) {
        StringBuilder buffer = new StringBuilder();
        llmPort.streamComplete(
                agent.getConfig().getSystemPrompt(),
                agent.getConfig().getModel(),
                lastUserMessage(agent),
                token -> {
                    buffer.append(token);
                    tokenConsumer.accept(token);
                });
        String answer = buffer.toString();
        agent.markCompleted(answer);
        return answer;
    }

    private String lastUserMessage(Agent agent) {
        List<SessionEvent> events = agent.getSessionEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).type() == SessionEvent.Type.USER) {
                return events.get(i).content();
            }
        }
        return "";
    }

    /**
     * [T] 经统一调度器执行工具调用：四层校验链 + 沙箱超时熔断（§1.2 模型与环境隔离）
     */
    private ToolResult dispatchThroughToolCenter(Agent agent, ToolCall call) {
        ToolCallRequest request = ToolCallRequest.builder()
                .toolId(call.name())
                .caller(agent.getId().getValue())
                .traceId(agent.getId().getValue())
                .arguments(call.arguments())
                .build();
        try {
            ToolCallResult callResult = toolDispatcher.dispatch(request);
            if (callResult.isSuccess()) {
                return ToolResult.success(callResult.getData() != null ? String.valueOf(callResult.getData()) : "");
            }
            return ToolResult.failure(callResult.getError());
        } catch (Exception e) {
            return ToolResult.failure("Tool dispatch error: " + e.getMessage());
        }
    }

    private HookResult fireToolHook(HookPoint point, Agent agent, String toolName, String payload) {
        if (hookEngine == null) {
            return null;
        }
        HookContext context = HookContext.of(point, agent.getId().getValue(), payload);
        context.put("toolName", toolName);
        HookResult result = hookEngine.fire(context);
        return result != null && result.getAction() == HookAction.ABORT ? result : null;
    }
}
