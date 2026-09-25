package org.skylark.langur.domain.harness.evaluation.alert;

import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;

import java.util.List;
import java.util.Optional;

/**
 * 阈值告警规则集（§10.3）- 覆盖四级分级的默认规则，阈值由 {@link AlertThresholds} 配置化驱动（P9）。
 * <ul>
 *   <li>P0 致命：安全拦截次数达阈值（越权/资损/泄露）</li>
 *   <li>P1 严重：执行延迟超上限 / 工具成功率低于下限 / 任务非正常终止</li>
 *   <li>P2 警告：Token 消耗超预警阈值 / 触发循环检测</li>
 * </ul>
 */
public final class ThresholdAlertRules {

    private ThresholdAlertRules() {
    }

    public static List<AlertRule> defaults(AlertThresholds thresholds) {
        AlertThresholds t = thresholds != null ? thresholds : AlertThresholds.defaults();
        return List.of(
                new SecurityInterceptionRule(t),
                new LatencyRule(t),
                new ToolSuccessRateRule(t),
                new AbnormalTerminationRule(),
                new TokenBudgetRule(t),
                new LoopDetectionRule());
    }

    /** P0：安全拦截达阈值 → 致命（阻断 + 人工介入）。 */
    static final class SecurityInterceptionRule implements AlertRule {
        private final AlertThresholds thresholds;

        SecurityInterceptionRule(AlertThresholds thresholds) {
            this.thresholds = thresholds;
        }

        @Override
        public String name() {
            return "SECURITY_INTERCEPTION";
        }

        @Override
        public Optional<Alert> evaluate(ExecutionMetrics metrics) {
            long interceptions = MetricReader.readLong(metrics, MetricDimension.SECURITY, "interceptions", 0L);
            if (interceptions >= thresholds.getInterceptionThreshold()) {
                return Optional.of(Alert.of(AlertLevel.P0_CRITICAL, name(), metrics.getTraceId(),
                        "安全拦截 " + interceptions + " 次，达致命阈值，需立即阻断并人工介入"));
            }
            return Optional.empty();
        }
    }

    /** P1：执行延迟超上限 → 严重（降级 + OnCall）。 */
    static final class LatencyRule implements AlertRule {
        private final AlertThresholds thresholds;

        LatencyRule(AlertThresholds thresholds) {
            this.thresholds = thresholds;
        }

        @Override
        public String name() {
            return "HIGH_LATENCY";
        }

        @Override
        public Optional<Alert> evaluate(ExecutionMetrics metrics) {
            long elapsed = MetricReader.readLong(metrics, MetricDimension.SCHEDULING, "elapsedMillis", 0L);
            if (elapsed > thresholds.getLatencyMillis()) {
                return Optional.of(Alert.of(AlertLevel.P1_SEVERE, name(), metrics.getTraceId(),
                        "执行耗时 " + elapsed + "ms 超过上限 " + thresholds.getLatencyMillis() + "ms"));
            }
            return Optional.empty();
        }
    }

    /** P1：工具成功率低于下限 → 严重（降级 + OnCall）。 */
    static final class ToolSuccessRateRule implements AlertRule {
        private final AlertThresholds thresholds;

        ToolSuccessRateRule(AlertThresholds thresholds) {
            this.thresholds = thresholds;
        }

        @Override
        public String name() {
            return "LOW_TOOL_SUCCESS_RATE";
        }

        @Override
        public Optional<Alert> evaluate(ExecutionMetrics metrics) {
            // successRate 为可选指标（0..1）；缺省 -1 表示未采集，跳过判定
            double rate = MetricReader.readDouble(metrics, MetricDimension.TOOL, "successRate", -1d);
            if (rate >= 0d && rate < thresholds.getToolSuccessRateFloor()) {
                return Optional.of(Alert.of(AlertLevel.P1_SEVERE, name(), metrics.getTraceId(),
                        "工具成功率 " + rate + " 低于下限 " + thresholds.getToolSuccessRateFloor()));
            }
            return Optional.empty();
        }
    }

    /** P1：任务非正常终止（FAILED/TERMINATED 且原因非 COMPLETED）→ 严重。 */
    static final class AbnormalTerminationRule implements AlertRule {
        @Override
        public String name() {
            return "ABNORMAL_TERMINATION";
        }

        @Override
        public Optional<Alert> evaluate(ExecutionMetrics metrics) {
            String reason = MetricReader.readString(metrics, MetricDimension.SCHEDULING, "terminateReason");
            if (reason != null && !reason.isBlank() && !"COMPLETED".equals(reason)) {
                return Optional.of(Alert.of(AlertLevel.P1_SEVERE, name(), metrics.getTraceId(),
                        "任务非正常终止：" + reason));
            }
            return Optional.empty();
        }
    }

    /** P2：Token 消耗超预警阈值 → 警告（终止 + 记录）。 */
    static final class TokenBudgetRule implements AlertRule {
        private final AlertThresholds thresholds;

        TokenBudgetRule(AlertThresholds thresholds) {
            this.thresholds = thresholds;
        }

        @Override
        public String name() {
            return "TOKEN_BUDGET";
        }

        @Override
        public Optional<Alert> evaluate(ExecutionMetrics metrics) {
            long tokens = MetricReader.readLong(metrics, MetricDimension.MODEL, "consumedTokens", 0L);
            if (tokens >= thresholds.getTokenThreshold()) {
                return Optional.of(Alert.of(AlertLevel.P2_WARNING, name(), metrics.getTraceId(),
                        "Token 消耗 " + tokens + " 达预警阈值 " + thresholds.getTokenThreshold()));
            }
            return Optional.empty();
        }
    }

    /** P2：触发循环检测 → 警告（终止 + 记录）。 */
    static final class LoopDetectionRule implements AlertRule {
        @Override
        public String name() {
            return "LOOP_DETECTED";
        }

        @Override
        public Optional<Alert> evaluate(ExecutionMetrics metrics) {
            String reason = MetricReader.readString(metrics, MetricDimension.SCHEDULING, "terminateReason");
            if ("LOOP_DETECTED".equals(reason)) {
                return Optional.of(Alert.of(AlertLevel.P2_WARNING, name(), metrics.getTraceId(),
                        "检测到执行循环，已强制终止"));
            }
            return Optional.empty();
        }
    }
}
