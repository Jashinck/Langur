package org.skylark.langur.infrastructure.harness.rsi;

import org.skylark.langur.infrastructure.harness.tool.skill.SkillSpec;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillStep;
import org.skylark.langur.infrastructure.harness.tool.skill.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能合成候选（R3，P11）——从高频成功轨迹归纳的<b>候选</b>技能提案，默认不直接生效。
 * <p>承载技能名/描述/权限/风险、编排步骤（{@link SkillStep}，可含 J10 {@code DECISION} 步骤）、
 * 来源轨迹与<b>诚实成本模型</b>：{@code llmRounds}（候选执行时的语义判定次数）对 {@code baselineLlmRounds}
 * （原 ReAct 路径逐轮推理的 LLM 轮次）。纯值对象，零 Spring 依赖；构造时防御性拷贝、越权审查依据
 * {@link #toolIds()} 抽取 TOOL_CALL 引用。</p>
 *
 * @param name              技能名（工具 ID {@code skill:{name}}）
 * @param description       描述
 * @param permission        权限标识（缺省空，经四层校验链审查）
 * @param riskLevel         风险等级（缺省 LOW）
 * @param steps             编排步骤（含 DECISION）
 * @param sourceTaskIds     来源成功轨迹任务标识（溯源）
 * @param llmRounds         候选执行时的语义判定/LLM 轮次（越少越省）
 * @param baselineLlmRounds 原 ReAct 路径的 LLM 推理轮次（各来源轨迹轮次之和）
 */
public record SkillSynthesisCandidate(String name,
                                      String description,
                                      String permission,
                                      String riskLevel,
                                      List<SkillStep> steps,
                                      List<String> sourceTaskIds,
                                      int llmRounds,
                                      int baselineLlmRounds) {

    public SkillSynthesisCandidate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SkillSynthesisCandidate name must not be blank");
        }
        description = (description == null) ? "" : description;
        permission = (permission == null) ? "" : permission;
        riskLevel = (riskLevel == null || riskLevel.isBlank()) ? "LOW" : riskLevel;
        steps = (steps == null) ? List.of() : List.copyOf(steps);
        sourceTaskIds = (sourceTaskIds == null) ? List.of() : List.copyOf(sourceTaskIds);
        llmRounds = Math.max(0, llmRounds);
        baselineLlmRounds = Math.max(0, baselineLlmRounds);
    }

    public static SkillSynthesisCandidate of(String name, String description, String permission,
                                             String riskLevel, List<SkillStep> steps,
                                             List<String> sourceTaskIds, int llmRounds, int baselineLlmRounds) {
        return new SkillSynthesisCandidate(name, description, permission, riskLevel,
                steps, sourceTaskIds, llmRounds, baselineLlmRounds);
    }

    /** 候选引用的外部工具 ID 集（越权审查依据）。 */
    public Set<String> toolIds() {
        return steps.stream()
                .filter(s -> s.getType() == StepType.TOOL_CALL)
                .map(SkillStep::getToolId)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** 转为注册用 {@link SkillSpec}（R3 注册阶段消费）。 */
    public SkillSpec toSkillSpec() {
        return SkillSpec.builder()
                .name(name)
                .description(description)
                .permission(permission)
                .riskLevel(riskLevel)
                .inputSchema(Map.of())
                .steps(steps)
                .build();
    }
}
