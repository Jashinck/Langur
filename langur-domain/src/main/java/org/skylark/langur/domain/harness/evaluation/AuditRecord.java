package org.skylark.langur.domain.harness.evaluation;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计记录 - 不可篡改全量留痕（附 checksum 校验）
 */
@Getter
@Builder
public class AuditRecord {

    private final String auditId;
    private final String traceId;
    private final String actor;
    private final String action;
    private final String detail;
    private final String checksum;
    private final Instant occurredAt;

    public static AuditRecord of(String traceId, String actor, String action,
                                 String detail, String checksum) {
        return AuditRecord.builder()
                .auditId(UUID.randomUUID().toString())
                .traceId(traceId)
                .actor(actor)
                .action(action)
                .detail(detail)
                .checksum(checksum)
                .occurredAt(Instant.now())
                .build();
    }
}
