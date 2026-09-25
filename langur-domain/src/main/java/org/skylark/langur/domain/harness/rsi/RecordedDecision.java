package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionAnswer;

/**
 * 录制判定（R0）。从轨迹仓库加载的一条历史 Jev 判定快照——承载<b>录制的答案</b>（{@link DecisionAnswer}，
 * 含 choice/value/confidence/distribution）、所属轮次、阈值类别与<b>当时实际走的分流</b>（{@code baselineRoute}）。
 * <p>回放时 {@link ReplayEngine} 只重算这些录制判定（绝不重新联网，C2/DD12）：以候选阈值/强制路由重算分流，
 * 与 {@code baselineRoute} 比对得出反事实指标差。纯 JDK record，零外部依赖（P1）。</p>
 *
 * @param key           判定问题键（如 {@code task-complete}/{@code layer-route}/{@code approval-auto}）
 * @param round         判定发生的执行轮次（对应 {@link TrajectoryStep#round()}）
 * @param answer        录制的判定答案（可空 → 回放按 fail-closed 处理）
 * @param category      阈值类别（决定回放选用哪档置信阈值）
 * @param baselineRoute 原始执行实际走的分流（基线复现依据）
 * @param latencyMillis 该次判定的往返延迟（计入回放延迟）
 */
public record RecordedDecision(String key,
                               int round,
                               DecisionAnswer answer,
                               ThresholdCategory category,
                               ReplayRoute baselineRoute,
                               long latencyMillis) {

    public RecordedDecision {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("RecordedDecision key must not be blank");
        }
        category = (category == null) ? ThresholdCategory.ROUTING : category;
        baselineRoute = (baselineRoute == null) ? ReplayRoute.RUN : baselineRoute;
        latencyMillis = Math.max(0L, latencyMillis);
    }
}
