package org.skylark.langur.infrastructure.harness.rsi;

import java.util.Set;

/**
 * 技能合成校验器（R3）——候选技能注册前的<b>离线确定性护栏</b>（R0 回放哲学：不联网、只比较）。
 * <p>三道审查，任一不过即拒绝（P11/P12）：① 空候选拒绝；② <b>越权审查</b>——候选引用的 TOOL_CALL 工具
 * 必须全部落在允许集 {@code allowedToolIds}（合成技能不得引入越权工具，红线）；③ <b>劣化审查</b>——候选语义
 * 判定轮次 {@code llmRounds} 不得高于基线 {@code baselineLlmRounds}（只降本不抬高）。纯 JDK 零 Spring 依赖。</p>
 */
public class SkillSynthesisValidator {

    /**
     * 校验候选。
     *
     * @param candidate       候选技能（可 null → 空候选拒绝）
     * @param allowedToolIds  允许引用的工具 ID 白名单（越权红线）
     * @return 裁定（ACCEPTED 方可注册）
     */
    public SkillSynthesisVerdict validate(SkillSynthesisCandidate candidate, Set<String> allowedToolIds) {
        if (candidate == null || candidate.isEmpty()) {
            return SkillSynthesisVerdict.rejected(SkillSynthesisVerdict.Reason.REJECTED_EMPTY, "empty candidate");
        }
        Set<String> allowed = allowedToolIds == null ? Set.of() : allowedToolIds;
        for (String toolId : candidate.toolIds()) {
            if (!allowed.contains(toolId)) {
                return SkillSynthesisVerdict.rejected(
                        SkillSynthesisVerdict.Reason.REJECTED_UNAUTHORIZED_TOOL,
                        "tool not allowed: " + toolId);
            }
        }
        if (candidate.llmRounds() > candidate.baselineLlmRounds()) {
            return SkillSynthesisVerdict.rejected(
                    SkillSynthesisVerdict.Reason.REJECTED_DEGRADED,
                    "llmRounds=" + candidate.llmRounds() + " > baseline=" + candidate.baselineLlmRounds());
        }
        return SkillSynthesisVerdict.accepted();
    }
}
