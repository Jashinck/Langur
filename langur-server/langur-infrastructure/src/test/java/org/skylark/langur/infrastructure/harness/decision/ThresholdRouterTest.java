package org.skylark.langur.infrastructure.harness.decision;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.infrastructure.harness.evaluation.MicrometerEvaluationService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * J3 - {@link ThresholdRouter} 阈值分流测试（离线确定性）。
 * <p>验收点：高置信按 CHOICE 值程序化分流；低置信/缺失 fail-closed（P12③）；
 * 每次分流发 {@code decision_route_counts} 指标（经 H5 Micrometer 桩注册表）；decide 透传保留 confidence。</p>
 */
class ThresholdRouterTest {

    private SimpleMeterRegistry registry;
    private ThresholdRouter router;
    private StubBackend backend;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        backend = new StubBackend();
        router = new ThresholdRouter(backend, DecisionThresholds.defaults(),
                new MicrometerEvaluationService(registry, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void shouldRouteHighConfidenceChoiceToProgrammaticActions() {
        assertEquals(ThresholdRouter.RouteAction.SKIP,
                router.route(DecisionAnswer.ofChoice("skip", 0.9, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.SKIP,
                router.route(DecisionAnswer.ofChoice("SKIP-STAGE", 0.9, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.BRANCH,
                router.route(DecisionAnswer.ofChoice("branch-to-stage", 0.8, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.APPROVE,
                router.route(DecisionAnswer.ofChoice("require-approval", 0.95, Map.of()), 0.9));
        assertEquals(ThresholdRouter.RouteAction.TERMINATE,
                router.route(DecisionAnswer.ofChoice("abort", 0.99, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.RUN,
                router.route(DecisionAnswer.ofChoice("run", 0.9, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.RUN,
                router.route(DecisionAnswer.ofChoice("something-else", 0.9, Map.of()), 0.75));
    }

    @Test
    void shouldRunForHighConfidenceNonChoiceAnswers() {
        assertEquals(ThresholdRouter.RouteAction.RUN,
                router.route(DecisionAnswer.ofProbability(0.95, 0.9), 0.75));
        assertEquals(ThresholdRouter.RouteAction.RUN,
                router.route(DecisionAnswer.ofScore(0.88, 0.85), 0.8));
    }

    @Test
    void shouldFailClosedOnLowConfidenceOrMissingAnswer() {
        assertEquals(ThresholdRouter.RouteAction.FAIL_CLOSED,
                router.route(DecisionAnswer.ofChoice("skip", 0.5, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.FAIL_CLOSED,
                router.route(DecisionAnswer.ofChoice("run", 0.0, Map.of()), 0.75));
        assertEquals(ThresholdRouter.RouteAction.FAIL_CLOSED, router.route(null, 0.75));
    }

    @Test
    void shouldEmitDecisionRouteCountsPerAction() {
        router.route(DecisionAnswer.ofChoice("skip", 0.9, Map.of()), 0.75);
        router.route(DecisionAnswer.ofChoice("skip", 0.9, Map.of()), 0.75);
        router.route(DecisionAnswer.ofChoice("abort", 0.3, Map.of()), 0.75);

        assertEquals(2.0, registry.get("langur.harness.event")
                .tag("dimension", "DECISION")
                .tag("key", "decision_route_counts")
                .tag("value", "skip")
                .counter().count());
        assertEquals(1.0, registry.get("langur.harness.event")
                .tag("dimension", "DECISION")
                .tag("key", "decision_route_counts")
                .tag("value", "fail_closed")
                .counter().count());
    }

    @Test
    void shouldPassThroughDecidePreservingConfidence() {
        DecisionRequest request = DecisionRequest.of("state",
                DecisionQuestion.choice("gate", "run or skip?", Map.of("run", "go", "skip", "skip")));
        DecisionResponse response = router.decide(request);

        assertSame(backend.response, response);
        assertEquals(0.9, response.answer("gate").confidence());
        assertEquals(1, backend.calls);
    }

    @Test
    void shouldExposeDelegateAndThresholds() {
        assertSame(backend, router.getDelegate());
        assertEquals(DecisionThresholds.defaults(), router.getThresholds());
        assertEquals(DecisionThresholds.defaults(),
                new ThresholdRouter(backend, null, null).getThresholds());
    }

    /** 记录调用的桩后端：固定返回高置信 CHOICE 答案。 */
    private static final class StubBackend implements DecisionPort {
        int calls;
        final DecisionResponse response = DecisionResponse.of(Map.of(
                "gate", DecisionAnswer.ofChoice("skip", 0.9, Map.of())));

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls++;
            return response;
        }
    }
}
