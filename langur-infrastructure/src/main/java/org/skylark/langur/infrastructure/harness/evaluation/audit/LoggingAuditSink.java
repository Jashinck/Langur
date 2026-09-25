package org.skylark.langur.infrastructure.harness.evaluation.audit;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.springframework.stereotype.Component;

/**
 * 日志型审计归档（H5）- 默认降级实现，全量留痕输出至日志（含 checksum 便于事后校验不可篡改性）。
 * <p>生产环境可另挂 MQ/对象存储 {@link AuditSink} 实现；多实现并存时全部归档。</p>
 */
@Slf4j
@Component
public class LoggingAuditSink implements AuditSink {

    @Override
    public void archive(AuditRecord record) {
        try {
            log.info("[AUDIT] id={} trace={} actor={} action={} checksum={} at={} detail={}",
                    record.getAuditId(), record.getTraceId(), record.getActor(), record.getAction(),
                    record.getChecksum(), record.getOccurredAt(), record.getDetail());
        } catch (RuntimeException e) {
            log.debug("Audit archive failed, silently dropped", e);
        }
    }
}
