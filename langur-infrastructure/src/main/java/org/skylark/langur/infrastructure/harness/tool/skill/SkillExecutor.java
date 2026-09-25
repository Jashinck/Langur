package org.skylark.langur.infrastructure.harness.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 技能执行引擎（§7.5 / H8）- 驱动 {@link SkillStep} 七类步骤：
 * <ul>
 *   <li>{@code TOOL_CALL} / {@code SUB_WORKFLOW} 经 {@link ToolDispatcher} 派发，因此嵌套工具同样走四层校验链；</li>
 *   <li>{@code CONDITION} 按表达式跳转到目标步骤或结束；</li>
 *   <li>{@code LLM_CALL} 经 {@link SkillLlmPort} 调模型（ACTION=M4 / REASONING=M5）；</li>
 *   <li>{@code LOOP} 带退出条件 + 次数上限，{@code PARALLEL} 并发分支后汇聚；</li>
 *   <li>{@code SUB_AGENT} 经 {@link SubAgentInvoker} 委派子 Agent（为 H12 铺路）。</li>
 * </ul>
 * <p>所有步骤共享一个全局步数硬上限（{@link #MAX_STEPS}）作为终止闸门，LOOP/PARALLEL 递归消耗同一预算，
 * 杜绝嵌套/循环构造出的失控执行。步骤输出写入执行上下文，可被后续步骤以 {@code ${stepId}} /
 * {@code ${stepId.field}} 引用；技能入参以 {@code ${input.x}} 引用。返回最后一个成功步骤的输出文本。</p>
 */
@Slf4j
public class SkillExecutor {

    /** 全局步数硬上限（终止闸门），防止 CONDITION 跳转 / LOOP / PARALLEL 构造出的失控执行。 */
    private static final int MAX_STEPS = 1000;
    /** LOOP 未显式给出次数上限时的兜底上限。 */
    private static final int DEFAULT_MAX_ITERATIONS = 100;
    private static final String END = "END";

    private final ToolDispatcher dispatcher;
    private final SkillExpressionResolver resolver;
    private final SkillLlmPort llmPort;
    private final SubAgentInvoker subAgentInvoker;
    private final ExecutorService parallelExecutor;

    /** 向后兼容构造：无 LLM/子 Agent 接缝，PARALLEL 使用默认守护线程池。 */
    public SkillExecutor(ToolDispatcher dispatcher, SkillExpressionResolver resolver) {
        this(dispatcher, resolver, null, null, null);
    }

    public SkillExecutor(ToolDispatcher dispatcher, SkillExpressionResolver resolver,
                         SkillLlmPort llmPort, SubAgentInvoker subAgentInvoker,
                         ExecutorService parallelExecutor) {
        this.dispatcher = dispatcher;
        this.resolver = resolver;
        this.llmPort = llmPort;
        this.subAgentInvoker = subAgentInvoker;
        this.parallelExecutor = parallelExecutor != null ? parallelExecutor : defaultExecutor();
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "skill-parallel");
            t.setDaemon(true);
            return t;
        });
    }

    public String execute(SkillSpec spec, Map<String, Object> inputs) {
        List<SkillStep> steps = spec.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new SkillExecutionException("skill has no steps: " + spec.getName());
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("input", inputs != null ? inputs : Map.of());
        String traceId = "skill-" + UUID.randomUUID();
        String caller = "skill:" + spec.getName();
        AtomicInteger guard = new AtomicInteger(0);
        return runSteps(steps, ctx, caller, traceId, guard, spec.getName());
    }

    /** 顺序（含 CONDITION 跳转）执行一段步骤，返回最后一步的输出文本；被 LOOP/PARALLEL 递归复用。 */
    private String runSteps(List<SkillStep> steps, Map<String, Object> ctx, String caller,
                            String traceId, AtomicInteger guard, String skillName) {
        String lastOutput = "";
        int index = 0;
        while (index < steps.size()) {
            tick(guard, skillName);
            SkillStep step = steps.get(index);
            if (step.getType() == StepType.CONDITION) {
                index = nextIndexOf(steps, step, ctx, index);
                if (index < 0) {
                    return lastOutput; // END
                }
                continue;
            }
            Object out = switch (step.getType()) {
                case TOOL_CALL -> execToolCall(step, ctx, caller, traceId);
                case SUB_WORKFLOW -> execSubWorkflow(step, ctx, caller, traceId);
                case LLM_CALL -> execLlmCall(step, ctx);
                case LOOP -> execLoop(step, ctx, caller, traceId, guard, skillName);
                case PARALLEL -> execParallel(step, ctx, caller, traceId, guard, skillName);
                case SUB_AGENT -> execSubAgent(step, ctx);
                case CONDITION -> throw new IllegalStateException("unreachable");
            };
            lastOutput = out == null ? "" : String.valueOf(out);
            String var = step.getOutputVar() != null ? step.getOutputVar() : step.getId();
            ctx.put(var, out);
            index++;
        }
        return lastOutput;
    }

    /** 计算 CONDITION 之后的下一个索引；返回 -1 表示命中 END 应结束当前步骤序列。 */
    private int nextIndexOf(List<SkillStep> steps, SkillStep step, Map<String, Object> ctx, int index) {
        boolean branch = resolver.evaluateCondition(step.getCondition(), ctx);
        String target = branch ? step.getOnTrue() : step.getOnFalse();
        if (target == null || target.isBlank()) {
            return index + 1;
        }
        if (END.equalsIgnoreCase(target.trim())) {
            return -1;
        }
        int next = indexOfId(steps, target.trim());
        if (next < 0) {
            throw new SkillExecutionException("condition [" + step.getId()
                    + "] target step not found: " + target);
        }
        return next;
    }

    private void tick(AtomicInteger guard, String skillName) {
        if (guard.incrementAndGet() > MAX_STEPS) {
            throw new SkillExecutionException("skill step limit exceeded (possible loop): " + skillName);
        }
    }

    @SuppressWarnings("unchecked")
    private Object execToolCall(SkillStep step, Map<String, Object> ctx, String caller, String traceId) {
        Map<String, Object> args = (Map<String, Object>) resolver.resolveValue(step.getArguments(), ctx);
        ToolCallResult result = dispatcher.dispatch(ToolCallRequest.builder()
                .toolId(step.getToolId())
                .caller(caller)
                .traceId(traceId)
                .arguments(args != null ? args : Map.of())
                .build());
        if (!result.isSuccess()) {
            throw new SkillExecutionException("step [" + step.getId() + "] tool "
                    + step.getToolId() + " failed: " + result.getError());
        }
        return result.getData() == null ? "" : String.valueOf(result.getData());
    }

    /** 嵌套技能：回派 {@link ToolDispatcher} 到 {@code skill:<ref>}，保留四层校验链。 */
    @SuppressWarnings("unchecked")
    private Object execSubWorkflow(SkillStep step, Map<String, Object> ctx, String caller, String traceId) {
        String ref = step.getSkillRef();
        if (ref == null || ref.isBlank()) {
            throw new SkillExecutionException("SUB_WORKFLOW step [" + step.getId() + "] missing skillRef");
        }
        Map<String, Object> args = (Map<String, Object>) resolver.resolveValue(step.getArguments(), ctx);
        String toolId = ref.startsWith("skill:") ? ref : "skill:" + ref;
        ToolCallResult result = dispatcher.dispatch(ToolCallRequest.builder()
                .toolId(toolId)
                .caller(caller)
                .traceId(traceId)
                .arguments(args != null ? args : Map.of())
                .build());
        if (!result.isSuccess()) {
            throw new SkillExecutionException("sub-workflow [" + step.getId() + "] "
                    + ref + " failed: " + result.getError());
        }
        return result.getData() == null ? "" : String.valueOf(result.getData());
    }

    private Object execLlmCall(SkillStep step, Map<String, Object> ctx) {
        if (llmPort == null) {
            throw new SkillExecutionException("LLM_CALL step [" + step.getId()
                    + "] requires an LLM backend, none configured");
        }
        String role = step.getModelRole() != null && !step.getModelRole().isBlank()
                ? step.getModelRole() : "ACTION";
        String systemPrompt = resolver.resolveString(step.getSystemPrompt(), ctx);
        String userPrompt = resolver.resolveString(step.getUserPrompt(), ctx);
        String out = llmPort.complete(role, systemPrompt, userPrompt);
        return out == null ? "" : out;
    }

    private Object execLoop(SkillStep step, Map<String, Object> ctx, String caller,
                            String traceId, AtomicInteger guard, String skillName) {
        List<SkillStep> body = step.getBody();
        if (body == null || body.isEmpty()) {
            return "";
        }
        int max = step.getMaxIterations() > 0 ? step.getMaxIterations() : DEFAULT_MAX_ITERATIONS;
        boolean conditional = step.getLoopCondition() != null && !step.getLoopCondition().isBlank();
        String lastOutput = "";
        int iterations = 0;
        while (iterations < max) {
            if (conditional && !resolver.evaluateCondition(step.getLoopCondition(), ctx)) {
                break;
            }
            iterations++;
            lastOutput = runSteps(body, ctx, caller, traceId, guard, skillName);
        }
        return lastOutput;
    }

    private Object execParallel(SkillStep step, Map<String, Object> ctx, String caller,
                                String traceId, AtomicInteger guard, String skillName) {
        List<List<SkillStep>> branches = step.getBranches();
        if (branches == null || branches.isEmpty()) {
            return List.of();
        }
        List<Future<String>> futures = new ArrayList<>();
        for (List<SkillStep> branch : branches) {
            Map<String, Object> branchCtx = new HashMap<>(ctx);
            futures.add(parallelExecutor.submit(
                    () -> runSteps(branch, branchCtx, caller, traceId, guard, skillName)));
        }
        List<String> gathered = new ArrayList<>();
        for (Future<String> future : futures) {
            try {
                gathered.add(future.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SkillExecutionException se) {
                    throw se;
                }
                throw new SkillExecutionException("parallel branch [" + step.getId()
                        + "] failed: " + (cause == null ? e.getMessage() : cause.getMessage()), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SkillExecutionException("parallel branch [" + step.getId() + "] interrupted", e);
            }
        }
        return gathered;
    }

    private Object execSubAgent(SkillStep step, Map<String, Object> ctx) {
        if (subAgentInvoker == null) {
            throw new SkillExecutionException("SUB_AGENT step [" + step.getId()
                    + "] requires a sub-agent invoker, none configured");
        }
        if (step.getAgentId() == null || step.getAgentId().isBlank()) {
            throw new SkillExecutionException("SUB_AGENT step [" + step.getId() + "] missing agentId");
        }
        String instruction = resolver.resolveString(step.getInstruction(), ctx);
        String out = subAgentInvoker.run(step.getAgentId(), instruction, ctx);
        return out == null ? "" : out;
    }

    private int indexOfId(List<SkillStep> steps, String id) {
        for (int i = 0; i < steps.size(); i++) {
            if (id.equals(steps.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }
}
