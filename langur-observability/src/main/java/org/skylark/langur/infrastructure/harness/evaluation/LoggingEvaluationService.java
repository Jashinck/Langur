package org.skylark.langur.infrastructure.harness.evaluation;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.alert.AlertEvaluator;
import org.skylark.langur.infrastructure.harness.evaluation.audit.AuditSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V 组件 - 日志型评估观测实现（H5 默认降级实现）。
 * <p>{@code langur.observability.evaluation=logging}（默认）时装配：四维指标输出日志，
 * 命中阈值经 {@link AlertEvaluator} 分级告警，审计记录扇出至全部 {@link AuditSink} 归档。
 * 上报失败静默降级，不影响主链路（P10）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.observability.evaluation", havingValue = "logging", matchIfMissing = true)
public class LoggingEvaluationService implements EvaluationService {

    private final AlertEvaluator alertEvaluator;
    private final List<AuditSink> auditSinks;

    public LoggingEvaluationService(AlertEvaluator alertEvaluator, List<AuditSink> auditSinks) {
        this.alertEvaluator = alertEvaluator;
        this.auditSinks = auditSinks != null ? List.copyOf(auditSinks) : List.of();
    }

    @Override
    public void report(ExecutionMetrics metrics) {
        try {
            log.info("[V][metrics] trace={} dimensions={}", metrics.getTraceId(), metrics.getDimensions());
            if (alertEvaluator != null) {
                alertEvaluator.evaluate(metrics);
            }
        } catch (RuntimeException e) {
            log.debug("Metric report failed, silently dropped", e);
        }
    }

    @Override
    public void audit(AuditRecord record) {
        try {
            log.info("[V][audit] trace={} actor={} action={} checksum={}",
                    record.getTraceId(), record.getActor(), record.getAction(), record.getChecksum());
            auditSinks.forEach(sink -> sink.archive(record));
        } catch (RuntimeException e) {
            log.debug("Audit report failed, silently dropped", e);
        }
    }
}
