package org.skylark.langur.domain.harness.evaluation;

/**
 * 指标维度 - 模型层 / 调度层 / 工具层 / 安全层 / 决策平面（J2，v3.0 §6.1 新增"决策维度"）
 */
public enum MetricDimension {
    MODEL,
    SCHEDULING,
    TOOL,
    SECURITY,
    /** 决策平面判定维度（J2）：decision_latency/confidence/fallback_rate/cost，route_counts 由 J3 补。 */
    DECISION
}
