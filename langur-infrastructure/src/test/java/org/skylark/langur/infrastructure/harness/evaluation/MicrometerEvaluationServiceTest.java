package org.skylark.langur.infrastructure.harness.evaluation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.evaluation.alert.Alert;
import org.skylark.langur.domain.harness.evaluation.alert.AlertChannel;
import org.skylark.langur.domain.harness.evaluation.alert.AlertEvaluator;
import org.skylark.langur.domain.harness.evaluation.alert.AlertThresholds;
import org.skylark.langur.infrastructure.harness.evaluation.audit.AuditSink;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H5 Prometheus/Micrometer 评估观测实现单测 - 校验四维指标映射、告警联动与审计扇出（SimpleMeterRegistry 内存验证）。
 */
class MicrometerEvaluationServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void shouldRecordNumericMetricAsSummary() {
        MicrometerEvaluationService service =
                new MicrometerEvaluationService(registry, null, List.of());
        ExecutionMetrics m = ExecutionMetrics.of("t-1");
        m.record(MetricDimension.MODEL, "consumedTokens", 800L);

        service.report(m);

        var summary = registry.find("langur.harness.consumedTokens").summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
        assertEquals(800d, summary.totalAmount());
        assertEquals("MODEL", summary.getId().getTag("dimension"));
    }

    @Test
    void shouldRecordStringMetricAsEventCounter() {
        MicrometerEvaluationService service =
                new MicrometerEvaluationService(registry, null, List.of());
        ExecutionMetrics m = ExecutionMetrics.of("t-2");
        m.record(MetricDimension.SCHEDULING, "terminateReason", "COMPLETED");

        service.report(m);

        var counter = registry.find("langur.harness.event")
                .tag("key", "terminateReason")
                .tag("value", "COMPLETED")
                .counter();
        assertNotNull(counter);
        assertEquals(1d, counter.count());
    }

    @Test
    void shouldIncrementExecutionsCounterPerReport() {
        MicrometerEvaluationService service =
                new MicrometerEvaluationService(registry, null, List.of());

        service.report(ExecutionMetrics.of("t-a"));
        service.report(ExecutionMetrics.of("t-b"));

        assertEquals(2d, registry.find("langur.harness.executions").counter().count());
    }

    @Test
    void shouldEvaluateAlertsOnReport() {
        List<Alert> received = new ArrayList<>();
        AlertChannel channel = received::add;
        AlertEvaluator evaluator = AlertEvaluator.withDefaults(AlertThresholds.defaults(), channel);
        MicrometerEvaluationService service =
                new MicrometerEvaluationService(registry, evaluator, List.of());

        ExecutionMetrics m = ExecutionMetrics.of("t-alert");
        m.record(MetricDimension.SECURITY, "interceptions", 1L);
        service.report(m);

        assertEquals(1, received.size());
        assertEquals("SECURITY_INTERCEPTION", received.get(0).getRule());
    }

    @Test
    void shouldFanOutAuditToSinksAndCount() {
        List<AuditRecord> archived = new ArrayList<>();
        AuditSink sink = archived::add;
        MicrometerEvaluationService service =
                new MicrometerEvaluationService(registry, null, List.of(sink));

        AuditRecord record = AuditRecord.of("t-audit", "agent", "TOOL_CALL", "{}", "checksum-1");
        service.audit(record);

        assertEquals(1, archived.size());
        assertEquals(record, archived.get(0));
        assertEquals(1d, registry.find("langur.harness.audit").tag("action", "TOOL_CALL").counter().count());
    }

    @Test
    void shouldIgnoreNullInputs() {
        MicrometerEvaluationService service =
                new MicrometerEvaluationService(registry, null, null);

        service.report(null);
        service.audit(null);

        assertTrue(registry.find("langur.harness.executions").counter() == null
                || registry.find("langur.harness.executions").counter().count() == 0d);
    }
}
