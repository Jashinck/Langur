package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * t_audit_log - 审计日志（不可篡改，含 checksum 校验链）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_audit_log", indexes = @Index(name = "idx_audit_trace_id", columnList = "traceId"))
public class AuditLogDO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String traceId;

    private String action;

    private String actor;

    @Lob
    private String detail;

    private String checksum;

    private Instant createdAt;
}
