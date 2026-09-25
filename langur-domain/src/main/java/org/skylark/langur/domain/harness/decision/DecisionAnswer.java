package org.skylark.langur.domain.harness.decision;

import java.util.Map;

/**
 * 单个判定结果（J1）。承载类型、选中枚举（CHOICE）、连续值（PROBABILITY/SCORE）、
 * 置信度（阈值分流依据）与完整分布（CHOICE，可观测/调优用）。
 * <p>纯 JDK record，零外部依赖（P1）。{@code distribution} 构造时防御性拷贝为不可变视图。</p>
 */
public record DecisionAnswer(DecisionType type,
                             String choice,
                             double value,
                             double confidence,
                             Map<String, Double> distribution) {

    public DecisionAnswer {
        distribution = (distribution == null) ? Map.of() : Map.copyOf(distribution);
    }

    /** CHOICE 结果：选中枚举 + 置信度 + 完整分布。 */
    public static DecisionAnswer ofChoice(String choice, double confidence, Map<String, Double> distribution) {
        return new DecisionAnswer(DecisionType.CHOICE, choice, 0d, confidence, distribution);
    }

    /** PROBABILITY（Jev noul）结果：0..1 可能性 + 置信度。 */
    public static DecisionAnswer ofProbability(double value, double confidence) {
        return new DecisionAnswer(DecisionType.PROBABILITY, null, value, confidence, Map.of());
    }

    /** SCORE 结果：0..1 连续评分 + 置信度。 */
    public static DecisionAnswer ofScore(double value, double confidence) {
        return new DecisionAnswer(DecisionType.SCORE, null, value, confidence, Map.of());
    }

    /** PROBABILITY/SCORE 的连续值是否达阈值（消费方分流便捷判定）。 */
    public boolean valueAtLeast(double threshold) {
        return value >= threshold;
    }

    /** 置信度是否达阈值（ThresholdRouter fail-closed 依据）。 */
    public boolean confidentAtLeast(double threshold) {
        return confidence >= threshold;
    }
}
