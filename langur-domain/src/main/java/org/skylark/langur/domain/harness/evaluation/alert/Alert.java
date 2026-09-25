package org.skylark.langur.domain.harness.evaluation.alert;

import lombok.Getter;

import java.time.Instant;

/**
 * 告警事件（§10.3）- 由 {@link AlertRule} 命中四维指标阈值后产出，交 {@link AlertChannel} 分发。
 */
@Getter
public class Alert {

    private final AlertLevel level;
    private final String rule;
    private final String traceId;
    private final String message;
    private final Instant occurredAt;

    private Alert(AlertLevel level, String rule, String traceId, String message, Instant occurredAt) {
        this.level = level;
        this.rule = rule;
        this.traceId = traceId;
        this.message = message;
        this.occurredAt = occurredAt;
    }

    public static Alert of(AlertLevel level, String rule, String traceId, String message) {
        return new Alert(level, rule, traceId, message, Instant.now());
    }

    @Override
    public String toString() {
        return "[" + level + "] " + rule + " trace=" + traceId + " : " + message;
    }
}
