package org.skylark.langur.domain.harness.evaluation.alert;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H5 告警分级规则引擎单测（§10.3）- 纯领域校验四级阈值命中、通道分发与失败静默降级（P10）。
 */
class AlertEvaluatorTest {

    private static ExecutionMetrics metrics(String traceId) {
        return ExecutionMetrics.of(traceId);
    }

    @Test
    void shouldFireCriticalWhenInterceptionReachesThreshold() {
        ExecutionMetrics m = metrics("t-sec");
        m.record(MetricDimension.SECURITY, "interceptions", 1L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        assertEquals(1, fired.size());
        Alert alert = fired.get(0);
        assertEquals(AlertLevel.P0_CRITICAL, alert.getLevel());
        assertEquals("SECURITY_INTERCEPTION", alert.getRule());
        assertEquals("BLOCK_AND_ESCALATE", alert.getLevel().defaultAction());
        assertEquals("t-sec", alert.getTraceId());
    }

    @Test
    void shouldFireSevereWhenLatencyExceedsThreshold() {
        ExecutionMetrics m = metrics("t-lat");
        m.record(MetricDimension.SCHEDULING, "elapsedMillis", 6_000L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        assertEquals(1, fired.size());
        assertEquals(AlertLevel.P1_SEVERE, fired.get(0).getLevel());
        assertEquals("HIGH_LATENCY", fired.get(0).getRule());
    }

    @Test
    void shouldFireWarningWhenTokenBudgetExceeded() {
        ExecutionMetrics m = metrics("t-tok");
        m.record(MetricDimension.MODEL, "consumedTokens", 40_000L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        assertEquals(1, fired.size());
        assertEquals(AlertLevel.P2_WARNING, fired.get(0).getLevel());
        assertEquals("TOKEN_BUDGET", fired.get(0).getRule());
    }

    @Test
    void shouldFireSevereWhenToolSuccessRateBelowFloor() {
        ExecutionMetrics m = metrics("t-tool");
        m.record(MetricDimension.TOOL, "successRate", 0.5d);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        assertEquals(1, fired.size());
        assertEquals("LOW_TOOL_SUCCESS_RATE", fired.get(0).getRule());
    }

    @Test
    void shouldSkipToolSuccessRateWhenNotCollected() {
        ExecutionMetrics m = metrics("t-tool-none");
        m.record(MetricDimension.TOOL, "calls", 3L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        assertTrue(fired.isEmpty());
    }

    @Test
    void shouldFireWarningOnLoopDetectedTermination() {
        ExecutionMetrics m = metrics("t-loop");
        m.record(MetricDimension.SCHEDULING, "terminateReason", "LOOP_DETECTED");

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        // LOOP_DETECTED 同时命中 AbnormalTermination(P1) 与 LoopDetection(P2)
        List<String> rules = fired.stream().map(Alert::getRule).toList();
        assertTrue(rules.contains("ABNORMAL_TERMINATION"));
        assertTrue(rules.contains("LOOP_DETECTED"));
    }

    @Test
    void shouldNotFireWhenMetricsHealthy() {
        ExecutionMetrics m = metrics("t-ok");
        m.record(MetricDimension.SCHEDULING, "elapsedMillis", 120L);
        m.record(MetricDimension.SCHEDULING, "terminateReason", "COMPLETED");
        m.record(MetricDimension.MODEL, "consumedTokens", 800L);
        m.record(MetricDimension.TOOL, "successRate", 1.0d);
        m.record(MetricDimension.SECURITY, "interceptions", 0L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(m);

        assertTrue(fired.isEmpty());
    }

    @Test
    void shouldDispatchEveryFiredAlertToChannel() {
        List<Alert> received = new ArrayList<>();
        AlertChannel channel = received::add;
        ExecutionMetrics m = metrics("t-dispatch");
        m.record(MetricDimension.SECURITY, "interceptions", 2L);
        m.record(MetricDimension.MODEL, "consumedTokens", 99_000L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), channel).evaluate(m);

        assertEquals(fired.size(), received.size());
        assertFalse(received.isEmpty());
    }

    @Test
    void shouldSwallowChannelFailureAndKeepEvaluating() {
        AlertChannel throwing = alert -> {
            throw new IllegalStateException("oncall down");
        };
        ExecutionMetrics m = metrics("t-fail");
        m.record(MetricDimension.SECURITY, "interceptions", 1L);
        m.record(MetricDimension.MODEL, "consumedTokens", 40_000L);

        List<Alert> fired = AlertEvaluator.withDefaults(AlertThresholds.defaults(), throwing).evaluate(m);

        // 通道异常被静默吞掉，规则仍全部评估完毕（P10）
        assertEquals(2, fired.size());
    }

    @Test
    void shouldReturnEmptyForNullMetrics() {
        assertTrue(AlertEvaluator.withDefaults(AlertThresholds.defaults(), AlertChannel.NOOP).evaluate(null).isEmpty());
    }

    @Test
    void shouldHonourCustomThresholds() {
        AlertThresholds tight = AlertThresholds.builder().latencyMillis(100L).build();
        ExecutionMetrics m = metrics("t-tight");
        m.record(MetricDimension.SCHEDULING, "elapsedMillis", 150L);

        List<Alert> fired = AlertEvaluator.withDefaults(tight, AlertChannel.NOOP).evaluate(m);

        assertEquals(1, fired.size());
        assertEquals("HIGH_LATENCY", fired.get(0).getRule());
    }
}
