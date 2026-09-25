package org.skylark.langur.domain.harness.evaluation.alert;

import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 告警评估器（§10.3）- 对单次执行指标依次套用 {@link AlertRule}，命中即经 {@link AlertChannel} 分发。
 * <p>纯领域实现（无 Spring 依赖），由 start 层装配。通道发送失败静默降级，不影响主链路（P10）。</p>
 */
public class AlertEvaluator {

    private final List<AlertRule> rules;
    private final AlertChannel channel;

    public AlertEvaluator(List<AlertRule> rules, AlertChannel channel) {
        this.rules = rules != null ? List.copyOf(rules) : List.of();
        this.channel = channel != null ? channel : AlertChannel.NOOP;
    }

    public static AlertEvaluator withDefaults(AlertThresholds thresholds, AlertChannel channel) {
        return new AlertEvaluator(ThresholdAlertRules.defaults(thresholds), channel);
    }

    /**
     * 评估并分发告警。
     *
     * @return 本次命中的告警（按规则顺序），无命中返回空列表
     */
    public List<Alert> evaluate(ExecutionMetrics metrics) {
        if (metrics == null) {
            return List.of();
        }
        List<Alert> fired = new ArrayList<>();
        for (AlertRule rule : rules) {
            rule.evaluate(metrics).ifPresent(alert -> {
                fired.add(alert);
                dispatch(alert);
            });
        }
        return fired;
    }

    private void dispatch(Alert alert) {
        try {
            channel.send(alert);
        } catch (RuntimeException ignored) {
            // P10：告警通道失败静默丢弃，不影响主链路
        }
    }
}
