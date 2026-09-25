package org.skylark.langur.domain.harness.evaluation.alert;

/**
 * 告警通道端口（§10.3）- 基础设施层对接日志/OnCall/IM/Webhook；发送失败须静默降级，不影响主链路（P10）。
 */
public interface AlertChannel {

    void send(Alert alert);

    /** 空通道：丢弃所有告警（未装配通道时的降级兜底）。 */
    AlertChannel NOOP = alert -> {
    };
}
