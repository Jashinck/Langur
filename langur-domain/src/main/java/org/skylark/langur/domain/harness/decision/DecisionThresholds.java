package org.skylark.langur.domain.harness.decision;

/**
 * 决策平面各插入点的置信阈值（J1，DD11 人工经验缺省值）。
 * <p>纯 JDK record，零外部依赖（P1）。低于对应阈值时消费方 fail-closed（回退规则/人审/中断，P12）；
 * D3 阶段这些阈值本身即 RSI（R4）离线调优对象，故为可版本化的策略面。</p>
 *
 * @param routing       层路由 advisory 阈值（J8，低于→回退现有规则）
 * @param approvalAuto  非 CRITICAL 审批自动放行阈值（J5，低于→人审）
 * @param artifactAccept 产物验收阈值（J6，低于→有界重试/打标）
 * @param completion    ReAct 完成判定阈值（J9，低于→以既有指纹/闸门为准）
 */
public record DecisionThresholds(double routing,
                                 double approvalAuto,
                                 double artifactAccept,
                                 double completion) {

    /** DD11 缺省：层路由 advisory。 */
    public static final double DEFAULT_ROUTING = 0.75d;
    /** DD11 缺省：非 CRITICAL 审批自动放行。 */
    public static final double DEFAULT_APPROVAL_AUTO = 0.90d;
    /** DD11 缺省：产物验收。 */
    public static final double DEFAULT_ARTIFACT_ACCEPT = 0.80d;
    /** DD11 缺省：ReAct 完成判定。 */
    public static final double DEFAULT_COMPLETION = 0.85d;

    /** DD11 人工经验缺省阈值组。 */
    public static DecisionThresholds defaults() {
        return new DecisionThresholds(DEFAULT_ROUTING, DEFAULT_APPROVAL_AUTO,
                DEFAULT_ARTIFACT_ACCEPT, DEFAULT_COMPLETION);
    }
}
