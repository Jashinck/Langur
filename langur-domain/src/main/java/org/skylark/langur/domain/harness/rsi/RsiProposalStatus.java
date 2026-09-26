package org.skylark.langur.domain.harness.rsi;

/**
 * RSI 提案生命周期状态（R-G 安全平面）。提案-验证-应用三段式 + 回滚，状态机由
 * {@link RsiSafetyPlane} 驱动：{@code PROPOSED → VALIDATED → APPLIED}，劣化/越权/限流拒绝置
 * {@code REJECTED}，已生效提案可回滚置 {@code ROLLED_BACK}。纯 JDK enum，零外部依赖（P1）。</p>
 */
public enum RsiProposalStatus {

    /** 已提交，待回放验证（候选，未生效）。 */
    PROPOSED,

    /** 回放验证通过（不劣于基线、无越权），待（高危）人审后应用。 */
    VALIDATED,

    /** 高危提案已过人审，可应用。 */
    APPROVED,

    /** 已生效（灰度/全量应用）。 */
    APPLIED,

    /** 被拒绝（劣化 / 越权 / 限流 / 深度超限）。 */
    REJECTED,

    /** 已回滚（撤销生效）。 */
    ROLLED_BACK
}
