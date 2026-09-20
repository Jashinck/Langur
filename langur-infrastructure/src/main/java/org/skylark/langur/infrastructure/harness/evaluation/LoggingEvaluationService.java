package org.skylark.langur.infrastructure.harness.evaluation;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.springframework.stereotype.Component;

/**
 * V 组件 - 日志型评估观测实现（默认降级实现）。
 * <p>生产环境可替换为 OpenTelemetry / Prometheus / MQ 上报；
 * 上报失败静默降级，不影响主链路（P10）。</p>
 */
@Slf4j
@Component
public class LoggingEvaluationService implements EvaluationService {

    @Override
    public void report(ExecutionMetrics metrics) {
        try {
            log.info("[V][metrics] trace={} dimensions={}", metrics.getTraceId(), metrics.getDimensions());
        } catch (RuntimeException e) {
            log.debug("Metric report failed, silently dropped", e);
        }
    }

    @Override
    public void audit(AuditRecord record) {
        try {
            log.info("[V][audit] trace={} actor={} action={} checksum={}",
                    record.getTraceId(), record.getActor(), record.getAction(), record.getChecksum());
        } catch (RuntimeException e) {
            log.debug("Audit report failed, silently dropped", e);
        }
    }
}
