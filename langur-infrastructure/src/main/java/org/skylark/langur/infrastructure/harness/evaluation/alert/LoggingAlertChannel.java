package org.skylark.langur.infrastructure.harness.evaluation.alert;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.evaluation.alert.Alert;
import org.skylark.langur.domain.harness.evaluation.alert.AlertChannel;
import org.springframework.stereotype.Component;

/**
 * 日志型告警通道（H5，§10.3）- 默认降级实现，按告警分级映射日志级别。
 * <p>生产可替换为 OnCall / IM / Webhook 通道；发送失败由 {@code AlertEvaluator} 静默吞掉（P10），
 * 此处亦不抛出，确保通知链路绝不影响主执行链路。</p>
 */
@Slf4j
@Component
public class LoggingAlertChannel implements AlertChannel {

    @Override
    public void send(Alert alert) {
        String line = "[ALERT][{}] action={} trace={} rule={} : {}";
        Object[] args = {alert.getLevel(), alert.getLevel().defaultAction(),
                alert.getTraceId(), alert.getRule(), alert.getMessage()};
        switch (alert.getLevel()) {
            case P0_CRITICAL -> log.error(line, args);
            case P1_SEVERE -> log.warn(line, args);
            default -> log.info(line, args);
        }
    }
}
