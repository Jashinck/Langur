package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionThresholds;

/**
 * 录制判定所属的阈值类别（R0）。回放时据此从 {@link DecisionThresholds} 选取对应置信阈值，
 * 使候选阈值调优（R4）能按插入点精确作用于该类判定。
 * <p>纯 JDK 值对象，零外部依赖（P1）。类别与 J3 {@code DecisionThresholds} 四档一一对应。</p>
 */
public enum ThresholdCategory {

    /** 层路由 advisory（J8）。 */
    ROUTING,
    /** 非 CRITICAL 审批自动放行（J5）——安全攸关，回放中"放松"即劣化拒绝（P12②只收紧）。 */
    APPROVAL_AUTO,
    /** 产物验收（J6）。 */
    ARTIFACT_ACCEPT,
    /** ReAct/Skill 完成判定（J9/J10）。 */
    COMPLETION;

    /** 从阈值组取本类别对应的置信阈值。 */
    public double thresholdOf(DecisionThresholds thresholds) {
        DecisionThresholds t = (thresholds == null) ? DecisionThresholds.defaults() : thresholds;
        return switch (this) {
            case ROUTING -> t.routing();
            case APPROVAL_AUTO -> t.approvalAuto();
            case ARTIFACT_ACCEPT -> t.artifactAccept();
            case COMPLETION -> t.completion();
        };
    }
}
