package org.skylark.langur.domain.service;

import lombok.RequiredArgsConstructor;
import org.skylark.langur.domain.harness.execution.RetryPolicy;
import org.skylark.langur.domain.harness.lifecycle.HookAction;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.FallbackStrategy;
import org.skylark.langur.domain.port.LLMPort;

import java.util.List;
import java.util.Map;
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

    public void attachHookEngine(LifecycleHookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    public void attachToolDispatcher(ToolDispatcher toolDispatcher) {
        this.toolDispatcher = toolDispatcher;
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
            decision = llmPort.decide(
                    agent.getConfig().getSystemPrompt(),
                    agent.getConfig().getModel(),
                    agent.getConversationHistory(),
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

        if (decision.isFinalAnswer()) {
            agent.markCompleted(decision.getFinalAnswer());
            return decision.getFinalAnswer();
        }

        PlanStep step = PlanStep.builder()
                .index(agent.getIterationCount())
                .thought(decision.getThought())
                .action(decision.getToolName())
                .actionInput(decision.getToolArguments())
                .status(StepStatus.RUNNING)
                .build();
        plan.addStep(step);

        Tool tool = agent.getTools().stream()
                .filter(t -> t.getName().equals(decision.getToolName()))
                .findFirst()
                .orElse(null);

        ToolResult result;
        if (tool == null) {
            result = ToolResult.failure("Tool not found: " + decision.getToolName());
        } else if (isToolCallBlocked(agent, decision)) {
            result = ToolResult.failure("Tool call rejected by lifecycle hook: " + decision.getToolName());
        } else {
            // [E] 工具执行 + 容错重试（§8.2 重试→降级）
            result = executeWithRetry(agent, tool, decision);
        }
        fireToolHook(HookPoint.AFTER_TOOL_CALL, agent, decision.getToolName(), result.getEffectiveContent());

        int lastIndex = plan.getSteps().size() - 1;
        plan.updateStep(lastIndex, result.isSuccess()
                ? step.withObservation(result.getContent())
                : step.withFailure(result.getError()));

        agent.addAssistantMessage("Thought: " + decision.getThought() +
                "\nAction: " + decision.getToolName() +
                "\nAction Input: " + decision.getToolArguments());
        agent.addToolResultMessage(decision.getToolName(), result.getEffectiveContent());

        return null;
    }

    /**
     * [L] BEFORE_TOOL_CALL 拦截：安全策略可在工具执行前阻断调用（四层纵深防御之执行安全）
     */
    private boolean isToolCallBlocked(Agent agent, LLMPort.LLMDecision decision) {
        return fireToolHook(HookPoint.BEFORE_TOOL_CALL, agent, decision.getToolName(),
                String.valueOf(decision.getToolArguments())) != null;
    }

    /**
     * [E] 工具执行 + 容错重试（T7）：失败按策略退避重试，重试耗尽后走降级话术。
     */
    private ToolResult executeWithRetry(Agent agent, Tool tool, LLMPort.LLMDecision decision) {
        RetryPolicy policy = retryPolicy != null ? retryPolicy : RetryPolicy.noRetry();
        ToolResult result = null;
        int attempt = 0;
        while (true) {
            attempt++;
            result = executeOnce(agent, tool, decision);
            if (result.isSuccess() || !policy.shouldRetry(attempt)) {
                break;
            }
            policy.backoff(attempt);
        }
        if (!result.isSuccess() && fallbackStrategy != null) {
            return ToolResult.failure(fallbackStrategy.onToolFailure(decision.getToolName(), result.getError()));
        }
        return result;
    }

    private ToolResult executeOnce(Agent agent, Tool tool, LLMPort.LLMDecision decision) {
        if (toolDispatcher != null) {
            // [T] 统一调度入口：白名单 → Schema → 动态权限 → 沙箱执行（§1.2 强制隔离）
            return dispatchThroughToolCenter(agent, decision);
        }
        try {
            return tool.execute(decision.getToolArguments());
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
        List<Map<String, String>> history = agent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> message = history.get(i);
            if ("user".equals(message.get("role"))) {
                return message.get("content");
            }
        }
        return "";
    }

    /**
     * [T] 经统一调度器执行工具调用：四层校验链 + 沙箱超时熔断（§1.2 模型与环境隔离）
     */
    private ToolResult dispatchThroughToolCenter(Agent agent, LLMPort.LLMDecision decision) {
        ToolCallRequest request = ToolCallRequest.builder()
                .toolId(decision.getToolName())
                .caller(agent.getId().getValue())
                .traceId(agent.getId().getValue())
                .arguments(decision.getToolArguments())
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
