package org.skylark.langur.domain.harness.evaluation.alert;

import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;

import java.util.Map;

/**
 * 四维指标读取工具 - 从 {@link ExecutionMetrics} 安全取值（缺失/类型不符回退默认），供各 {@link AlertRule} 复用。
 */
final class MetricReader {

    private MetricReader() {
    }

    static long readLong(ExecutionMetrics metrics, MetricDimension dimension, String key, long fallback) {
        Object value = raw(metrics, dimension, key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    static double readDouble(ExecutionMetrics metrics, MetricDimension dimension, String key, double fallback) {
        Object value = raw(metrics, dimension, key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    static String readString(ExecutionMetrics metrics, MetricDimension dimension, String key) {
        Object value = raw(metrics, dimension, key);
        return value != null ? String.valueOf(value) : null;
    }

    private static Object raw(ExecutionMetrics metrics, MetricDimension dimension, String key) {
        if (metrics == null) {
            return null;
        }
        Map<String, Object> values = metrics.getDimensions().get(dimension);
        return values != null ? values.get(key) : null;
    }
}
