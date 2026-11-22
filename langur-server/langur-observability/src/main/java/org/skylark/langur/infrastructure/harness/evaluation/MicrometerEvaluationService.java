package org.skylark.langur.infrastructure.harness.evaluation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.evaluation.alert.AlertEvaluator;
import org.skylark.langur.infrastructure.harness.evaluation.audit.AuditSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * V 组件 - Prometheus/Micrometer 评估观测实现（H5，§10）。
 * <p>{@code langur.observability.evaluation=prometheus} 时装配：四维指标映射至 {@link MeterRegistry}
 * （数值型 → DistributionSummary，枚举/字符串型 → 打点 Counter），经 {@code /actuator/prometheus} 暴露；
 * 命中阈值经 {@link AlertEvaluator} 分级告警；审计扇出至全部 {@link AuditSink}（MQ 归档挂载点）。
 * 全流程失败静默降级，不影响主链路（P10）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.observability.evaluation", havingValue = "prometheus")
public class MicrometerEvaluationService implements EvaluationService {

    private static final String METRIC_PREFIX = "langur.harness.";

    private final MeterRegistry registry;
    private final AlertEvaluator alertEvaluator;
    private final List<AuditSink> auditSinks;

    public MicrometerEvaluationService(MeterRegistry registry,
                                       AlertEvaluator alertEvaluator,
                                       List<AuditSink> auditSinks) {
        this.registry = registry;
        this.alertEvaluator = alertEvaluator;
        this.auditSinks = auditSinks != null ? List.copyOf(auditSinks) : List.of();
    }

    @Override
    public void report(ExecutionMetrics metrics) {
        if (metrics == null) {
            return;
        }
        try {
            Counter.builder(METRIC_PREFIX + "executions").register(registry).increment();
            for (Map.Entry<MetricDimension, Map<String, Object>> entry : metrics.getDimensions().entrySet()) {
                String dimension = entry.getKey().name();
                entry.getValue().forEach((key, value) -> record(dimension, key, value));
            }
            if (alertEvaluator != null) {
                alertEvaluator.evaluate(metrics);
            }
        } catch (RuntimeException e) {
            log.debug("Metric report failed, silently dropped", e);
        }
    }

    @Override
    public void audit(AuditRecord record) {
        if (record == null) {
            return;
        }
        try {
            Counter.builder(METRIC_PREFIX + "audit").tag("action", sanitize(record.getAction()))
                    .register(registry).increment();
            auditSinks.forEach(sink -> sink.archive(record));
        } catch (RuntimeException e) {
            log.debug("Audit report failed, silently dropped", e);
        }
    }

    private void record(String dimension, String key, Object value) {
        if (value instanceof Number number) {
            DistributionSummary.builder(METRIC_PREFIX + sanitize(key))
                    .tag("dimension", dimension)
                    .register(registry)
                    .record(number.doubleValue());
        } else if (value != null) {
            Counter.builder(METRIC_PREFIX + "event")
                    .tag("dimension", dimension)
                    .tag("key", sanitize(key))
                    .tag("value", sanitize(String.valueOf(value)))
                    .register(registry)
                    .increment();
        }
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
