package org.skylark.langur.domain.harness.rsi;

/**
 * 候选策略类型（R0）。回放引擎对不同类别候选的确定性能力不同（DD5 录制回放）：
 * <ul>
 *   <li>{@link #THRESHOLD}/{@link #ROUTE}：改变的是"对<b>录制判定</b>所施的策略"——回放可完全离线确定性重算分流，
 *       是 R0 的反事实核心，也是 R4 离线调优的作用面。</li>
 *   <li>{@link #PROMPT}/{@link #SKILL}/{@link #PARAMS}：改变的是 LLM 生成本身——离线无法反事实重新生成，
 *       回放<b>重放录制响应</b>复现基线（确定性），指标与基线一致 → {@link BaselineComparison.Verdict#NEUTRAL}，
 *       如实反映"离线无生成级反事实信号"（此类候选的真实增益须由 R3/R4 的在线灰度 + 录制回放样本评估）。</li>
 *   <li>{@link #BASELINE}：原策略重放，用于断言"复现原结果（含决策分流路径一致）"。</li>
 * </ul>
 */
public enum CandidateKind {

    /** 原策略（复现基线）。 */
    BASELINE,
    /** 置信阈值调优（重算录制判定的分流）。 */
    THRESHOLD,
    /** 强制路由覆盖（按判定 key 指定分流）。 */
    ROUTE,
    /** Prompt 模板变更（离线重放录制响应，无生成级反事实）。 */
    PROMPT,
    /** Skill 变更（同上）。 */
    SKILL,
    /** 超参变更（同上）。 */
    PARAMS;

    /** 是否为"策略级"候选——回放可离线确定性重算分流并产生指标差。 */
    public boolean isPolicyLevel() {
        return this == THRESHOLD || this == ROUTE;
    }
}
