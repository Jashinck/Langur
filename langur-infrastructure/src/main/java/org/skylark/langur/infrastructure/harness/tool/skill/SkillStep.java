package org.skylark.langur.infrastructure.harness.tool.skill;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 技能步骤（§7.5）- 一条编排指令。按 {@link #getType()} 区分语义：
 * <ul>
 *   <li>{@code TOOL_CALL}：以 {@link #getToolId()} 调用工具，{@link #getArguments()} 支持
 *       {@code ${input.x}} / {@code ${stepId.field}} 占位符，结果写入 {@link #getOutputVar()}（缺省用步骤 id）。</li>
 *   <li>{@code CONDITION}：对 {@link #getCondition()} 求值，真跳 {@link #getOnTrue()}、假跳 {@link #getOnFalse()}；
 *       目标为 {@code null} 表示顺序执行下一步，{@code END} 表示结束技能。</li>
 * </ul>
 */
@Getter
@Builder
public class SkillStep {

    /** 步骤标识，条件跳转与占位符引用以此定位。 */
    private final String id;
    private final StepType type;

    // ---- TOOL_CALL ----
    private final String toolId;
    private final Map<String, Object> arguments;
    private final String outputVar;

    // ---- CONDITION ----
    private final String condition;
    private final String onTrue;
    private final String onFalse;

    public static SkillStep toolCall(String id, String toolId, Map<String, Object> arguments) {
        return SkillStep.builder().id(id).type(StepType.TOOL_CALL)
                .toolId(toolId).arguments(arguments).outputVar(id).build();
    }

    public static SkillStep toolCall(String id, String toolId, Map<String, Object> arguments, String outputVar) {
        return SkillStep.builder().id(id).type(StepType.TOOL_CALL)
                .toolId(toolId).arguments(arguments).outputVar(outputVar).build();
    }

    public static SkillStep condition(String id, String condition, String onTrue, String onFalse) {
        return SkillStep.builder().id(id).type(StepType.CONDITION)
                .condition(condition).onTrue(onTrue).onFalse(onFalse).build();
    }
}
