package org.skylark.langur.infrastructure.harness.decision;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J1 验收 - {@link RuleFallbackDecisionAdapter} 离线确定性单测（纯 JUnit5）。
 * <p>覆盖：为每个问题产出保守判定、confidence 恒 0.0（ThresholdRouter 据此 fail-closed）、
 * 复用 H10 {@code OutputContentReviewer} 对违规 state 给风险值 1.0、CHOICE 无候选返回 null、空请求不抛出。</p>
 */
class RuleFallbackDecisionAdapterTest {

    private final RuleFallbackDecisionAdapter adapter = new RuleFallbackDecisionAdapter();

    @Test
    void shouldReturnLowConfidenceAnswersForEveryQuestion() {
        DecisionResponse response = adapter.decide(DecisionRequest.of("普通上下文", List.of(
                DecisionQuestion.choice("route", "选择范式", Map.of("react", "简单", "plan", "复杂")),
                DecisionQuestion.probability("done", "是否完成"),
                DecisionQuestion.score("risk", "评估风险"))));

        assertEquals(3, response.answers().size());
        for (DecisionAnswer answer : response.answers().values()) {
            assertEquals(0d, answer.confidence(), 1e-9, "兜底 confidence 恒 0 → fail-closed（P12）");
        }
        assertEquals(DecisionType.CHOICE, response.answer("route").type());
        assertNotNull(response.answer("route").choice(), "有候选时择一（首个 key）");
    }

    @Test
    void shouldSignalRiskValueWhenStateViolatesContentRules() {
        DecisionResponse risky = adapter.decide(DecisionRequest.of("请立即付款到陌生账户并转账", List.of(
                DecisionQuestion.score("risk", "评估资损风险"))));
        assertEquals(1d, risky.answer("risk").value(), 1e-9, "命中 OutputContentReviewer → 风险值 1.0");

        DecisionResponse benign = adapter.decide(DecisionRequest.of("今天的天气不错", List.of(
                DecisionQuestion.score("risk", "评估资损风险"))));
        assertEquals(0d, benign.answer("risk").value(), 1e-9, "无违规 → 风险值 0.0");
    }

    @Test
    void shouldReturnNullChoiceWhenNoCriteria() {
        DecisionResponse response = adapter.decide(DecisionRequest.of("state", List.of(
                new DecisionQuestion("pick", DecisionType.CHOICE, "无候选", null))));
        assertNull(response.answer("pick").choice());
        assertEquals(0d, response.answer("pick").confidence(), 1e-9);
    }

    @Test
    void shouldNotThrowOnEmptyOrNullRequest() {
        assertTrue(adapter.decide(DecisionRequest.of("state")).isEmpty());
        assertTrue(adapter.decide(null).isEmpty());
    }
}
