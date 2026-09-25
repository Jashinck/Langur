package org.skylark.langur.domain.harness.decision;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J1 验收 - 决策平面 domain 值对象纯 JUnit5 单测（无 Mockito，P1 纯 JDK）。
 * <p>覆盖：{@link DecisionType} wire 名双向映射（choice/noul/score）、record 防御性拷贝与不可变、
 * 便捷工厂、{@link DecisionResponse} 空计量归一与查键、{@link DecisionThresholds} DD11 缺省值。</p>
 */
class DecisionModelTest {

    @Test
    void shouldMapDecisionTypeToJevWireNames() {
        assertEquals("choice", DecisionType.CHOICE.wireName());
        assertEquals("noul", DecisionType.PROBABILITY.wireName());
        assertEquals("score", DecisionType.SCORE.wireName());
        assertEquals(DecisionType.CHOICE, DecisionType.fromWireName("choice"));
        assertEquals(DecisionType.PROBABILITY, DecisionType.fromWireName("NOUL"));
        assertEquals(DecisionType.SCORE, DecisionType.fromWireName("score"));
        assertNull(DecisionType.fromWireName("unknown"));
        assertNull(DecisionType.fromWireName(null));
    }

    @Test
    void shouldCopyCollectionsDefensivelyAndRejectMutation() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("react", "简单抽取");
        DecisionQuestion question = DecisionQuestion.choice("route", "选择执行器", criteria);
        criteria.put("plan", "复杂多步");

        assertEquals(1, question.criteria().size(), "构造后原 Map 变更不应影响值对象");
        assertThrows(UnsupportedOperationException.class,
                () -> question.criteria().put("x", "y"), "criteria 应不可变");
    }

    @Test
    void shouldNormalizeNullCollectionsToEmptyImmutable() {
        DecisionQuestion question = new DecisionQuestion("done", DecisionType.PROBABILITY, "是否完成", null);
        assertTrue(question.criteria().isEmpty());

        DecisionRequest request = new DecisionRequest("state", null, null);
        assertTrue(request.questions().isEmpty());
        assertTrue(request.hasNoQuestions());

        DecisionAnswer answer = new DecisionAnswer(DecisionType.CHOICE, "a", 0d, 0.9d, null);
        assertTrue(answer.distribution().isEmpty());
    }

    @Test
    void shouldBuildTypedAnswersViaFactories() {
        DecisionAnswer choice = DecisionAnswer.ofChoice("react", 0.92d, Map.of("react", 0.92d, "plan", 0.08d));
        assertEquals(DecisionType.CHOICE, choice.type());
        assertEquals("react", choice.choice());
        assertTrue(choice.confidentAtLeast(0.75d));
        assertFalse(choice.confidentAtLeast(0.95d));
        assertEquals(0.92d, choice.distribution().get("react"), 1e-9);

        DecisionAnswer noul = DecisionAnswer.ofProbability(0.97d, 0.97d);
        assertEquals(DecisionType.PROBABILITY, noul.type());
        assertTrue(noul.valueAtLeast(0.85d));

        DecisionAnswer score = DecisionAnswer.ofScore(0.30d, 0.88d);
        assertEquals(DecisionType.SCORE, score.type());
        assertFalse(score.valueAtLeast(0.80d));
    }

    @Test
    void shouldDefaultResponseUsageToEmptyAndLookupByKey() {
        DecisionResponse response = DecisionResponse.of(Map.of(
                "route", DecisionAnswer.ofChoice("react", 0.9d, Map.of()),
                "done", DecisionAnswer.ofProbability(0.99d, 0.99d)));

        assertFalse(response.isEmpty());
        assertTrue(response.usage().isEmpty(), "缺省计量应为空");
        assertEquals("react", response.answer("route").choice());
        assertNull(response.answer("missing"));
    }

    @Test
    void shouldExposeDd11DefaultThresholds() {
        DecisionThresholds thresholds = DecisionThresholds.defaults();
        assertEquals(0.75d, thresholds.routing(), 1e-9);
        assertEquals(0.90d, thresholds.approvalAuto(), 1e-9);
        assertEquals(0.80d, thresholds.artifactAccept(), 1e-9);
        assertEquals(0.85d, thresholds.completion(), 1e-9);
    }

    @Test
    void shouldCarryModelAndBatchQuestionsInRequest() {
        DecisionRequest request = DecisionRequest.of("合同正文", "jev-1.13.0", java.util.List.of(
                DecisionQuestion.score("risk", "评估风险"),
                DecisionQuestion.probability("done", "是否完成")));

        assertEquals("jev-1.13.0", request.model());
        assertEquals(2, request.questions().size());
        assertFalse(request.hasNoQuestions());
    }
}
