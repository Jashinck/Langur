package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.AgentDO;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentJpaRepository extends JpaRepository<AgentDO, String> {
}
