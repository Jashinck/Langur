package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.AuditLogDO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogDO, Long> {

    List<AuditLogDO> findByTraceId(String traceId);
}
