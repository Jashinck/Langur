package org.skylark.langur.infrastructure.harness.rsi;

/**
 * 技能合成裁定（R3）。{@link SkillSynthesisValidator} 对候选技能的一票否决/放行结论。
 * <p>纯值对象，零 Spring 依赖。{@code ACCEPTED} 方允许注册；其余均拒绝并携原因（供告警/审计）。</p>
 */
public record SkillSynthesisVerdict(Reason reason, String detail) {

    public enum Reason {
        /** 放行：候选合法且不劣于基线。 */
        ACCEPTED,
        /** 空候选：无可编排步骤。 */
        REJECTED_EMPTY,
        /** 越权：候选引用了允许集之外的工具（P11 红线）。 */
        REJECTED_UNAUTHORIZED_TOOL,
        /** 劣化：候选语义判定轮次高于基线（只降本红线）。 */
        REJECTED_DEGRADED
    }

    public SkillSynthesisVerdict {
        reason = (reason == null) ? Reason.REJECTED_EMPTY : reason;
        detail = (detail == null) ? "" : detail;
    }

    public boolean isAccepted() {
        return reason == Reason.ACCEPTED;
    }

    public static SkillSynthesisVerdict accepted() {
        return new SkillSynthesisVerdict(Reason.ACCEPTED, "");
    }

    public static SkillSynthesisVerdict rejected(Reason reason, String detail) {
        return new SkillSynthesisVerdict(reason, detail);
    }
}
