package org.skylark.langur.domain.harness.rsi;

/**
 * 轨迹步骤（R0）。一条历史执行轮次的成本快照，来自 S 组件 {@code StateSnapshot} / {@code t_execution_round}。
 * <p>回放的成本模型以此为单位：{@link ReplayRoute#SKIP} 避免该轮成本、{@link ReplayRoute#TERMINATE} 截断其后
 * 所有轮次。纯 JDK record，零外部依赖（P1）。</p>
 *
 * @param round         轮次序号（与 {@link RecordedDecision#round()} 对齐）
 * @param action        该轮动作标识（可观测/审计用，回放不依赖其语义）
 * @param tokens        该轮消耗的 Token（复用 H1 真实计量）
 * @param latencyMillis 该轮耗时（毫秒）
 */
public record TrajectoryStep(int round,
                             String action,
                             long tokens,
                             long latencyMillis) {

    public TrajectoryStep {
        tokens = Math.max(0L, tokens);
        latencyMillis = Math.max(0L, latencyMillis);
        action = (action == null) ? "" : action;
    }

    /** 便捷工厂。 */
    public static TrajectoryStep of(int round, String action, long tokens, long latencyMillis) {
        return new TrajectoryStep(round, action, tokens, latencyMillis);
    }
}
