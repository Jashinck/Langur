package org.skylark.langur.infrastructure.harness.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 技能执行引擎（§7.5）- 顺序驱动 {@link SkillStep}：TOOL_CALL 经 {@link ToolDispatcher} 派发
 * （因此嵌套工具同样走四层校验链），CONDITION 按表达式跳转到目标步骤或结束。
 * <p>步骤输出写入执行上下文，可被后续步骤以 {@code ${stepId}} / {@code ${stepId.field}} 引用；
 * 技能入参以 {@code ${input.x}} 引用。返回最后一个成功步骤的输出文本。</p>
 */
@Slf4j
public class SkillExecutor {

    /** 跳转步数硬上限，防止 CONDITION 目标构造出的死循环。 */
    private static final int MAX_STEPS = 1000;
    private static final String END = "END";

    private final ToolDispatcher dispatcher;
    private final SkillExpressionResolver resolver;

    public SkillExecutor(ToolDispatcher dispatcher, SkillExpressionResolver resolver) {
        this.dispatcher = dispatcher;
        this.resolver = resolver;
    }

    @SuppressWarnings("unchecked")
    public String execute(SkillSpec spec, Map<String, Object> inputs) {
        List<SkillStep> steps = spec.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new SkillExecutionException("skill has no steps: " + spec.getName());
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("input", inputs != null ? inputs : Map.of());
        String traceId = "skill-" + UUID.randomUUID();
        String caller = "skill:" + spec.getName();

        String lastOutput = "";
        int index = 0;
        int guard = 0;
        while (index < steps.size()) {
            if (++guard > MAX_STEPS) {
                throw new SkillExecutionException("skill step limit exceeded (possible loop): " + spec.getName());
            }
            SkillStep step = steps.get(index);
            if (step.getType() == StepType.TOOL_CALL) {
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
                lastOutput = result.getData() == null ? "" : String.valueOf(result.getData());
                String var = step.getOutputVar() != null ? step.getOutputVar() : step.getId();
                ctx.put(var, lastOutput);
                index++;
            } else {
                boolean branch = resolver.evaluateCondition(step.getCondition(), ctx);
                String target = branch ? step.getOnTrue() : step.getOnFalse();
                if (target == null || target.isBlank()) {
                    index++;
                } else if (END.equalsIgnoreCase(target.trim())) {
                    break;
                } else {
                    int next = indexOfId(steps, target.trim());
                    if (next < 0) {
                        throw new SkillExecutionException("condition [" + step.getId()
                                + "] target step not found: " + target);
                    }
                    index = next;
                }
            }
        }
        return lastOutput;
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
