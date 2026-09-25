package org.skylark.langur.domain.harness.decision;

import java.util.List;

/**
 * 决策平面判定请求（J1）。承载原始上下文 {@code state}（串/JSON）、后端模型标识与一批判定问题。
 * <p>纯 JDK record，零外部依赖（P1）。{@code model} 可空（{@code null}→适配器用配置默认模型）；
 * {@code questions} 构造时防御性拷贝为不可变列表，空列表由消费方自行短路。</p>
 */
public record DecisionRequest(String state,
                              String model,
                              List<DecisionQuestion> questions) {

    public DecisionRequest {
        questions = (questions == null) ? List.of() : List.copyOf(questions);
    }

    /** 便捷工厂：默认模型（{@code null}）+ 变长问题。 */
    public static DecisionRequest of(String state, DecisionQuestion... questions) {
        return new DecisionRequest(state, null, List.of(questions));
    }

    /** 便捷工厂：默认模型（{@code null}）+ 问题列表。 */
    public static DecisionRequest of(String state, List<DecisionQuestion> questions) {
        return new DecisionRequest(state, null, questions);
    }

    /** 便捷工厂：显式模型 + 问题列表。 */
    public static DecisionRequest of(String state, String model, List<DecisionQuestion> questions) {
        return new DecisionRequest(state, model, questions);
    }

    /** 无问题（消费方应短路，不发起后端往返）。 */
    public boolean hasNoQuestions() {
        return questions.isEmpty();
    }
}
