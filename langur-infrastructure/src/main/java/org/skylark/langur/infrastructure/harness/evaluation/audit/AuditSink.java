package org.skylark.langur.infrastructure.harness.evaluation.audit;

import org.skylark.langur.domain.harness.evaluation.AuditRecord;

/**
 * 审计归档通道（H5，§10）- 不可篡改全量留痕的落库/上报出口。
 * <p>默认 {@link LoggingAuditSink} 输出日志；生产可挂 MQ（如 RocketMQ）异步上报或对象存储归档。
 * 归档失败须静默降级，不影响主链路（P10）。</p>
 */
public interface AuditSink {

    void archive(AuditRecord record);
}
