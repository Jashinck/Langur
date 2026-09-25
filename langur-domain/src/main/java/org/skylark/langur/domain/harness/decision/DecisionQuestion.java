package org.skylark.langur.domain.harness.decision;

import java.util.Map;

/**
 * 单个判定问题（J1）。一次 {@link DecisionRequest} 可含多问题（批量/投机扇出），{@code state} 只发一次。
 * <p>纯 JDK record，零外部依赖（P1）。{@code criteria} 仅 CHOICE 使用（候选枚举 key→描述），
 * 构造时防御性拷贝为不可变视图；其它类型可传 {@code null}。</p>
 */
public record DecisionQuestion(String key,
                               DecisionType type,
                               String instructions,
                               Map<String, String> criteria) {

    public DecisionQuestion {
        criteria = (criteria == null) ? Map.of() : Map.copyOf(criteria);
    }

    /** CHOICE 问题：从 {@code criteria} 候选枚举中择一。 */
    public static DecisionQuestion choice(String key, String instructions, Map<String, String> criteria) {
        return new DecisionQuestion(key, DecisionType.CHOICE, instructions, criteria);
    }

    /** PROBABILITY（Jev noul）问题：0..1 可能性判定。 */
    public static DecisionQuestion probability(String key, String instructions) {
        return new DecisionQuestion(key, DecisionType.PROBABILITY, instructions, Map.of());
    }

    /** SCORE 问题：0..1 连续评分（风险分级/产物验收）。 */
    public static DecisionQuestion score(String key, String instructions) {
        return new DecisionQuestion(key, DecisionType.SCORE, instructions, Map.of());
    }
}
