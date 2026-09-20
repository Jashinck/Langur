package org.skylark.langur.domain.harness.evaluation;

import lombok.Getter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 四维执行指标 - 按维度聚合的运行时度量
 */
@Getter
public class ExecutionMetrics {

    private final String traceId;
    private final Instant occurredAt;
    private final Map<MetricDimension, Map<String, Object>> dimensions;

    private ExecutionMetrics(String traceId, Instant occurredAt) {
        this.traceId = traceId;
        this.occurredAt = occurredAt;
        this.dimensions = new HashMap<>();
    }

    public static ExecutionMetrics of(String traceId) {
        return new ExecutionMetrics(traceId, Instant.now());
    }

    public void record(MetricDimension dimension, String key, Object value) {
        dimensions.computeIfAbsent(dimension, d -> new HashMap<>()).put(key, value);
    }
}
