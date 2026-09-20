package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.ToolDefinitionDO;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolDefinitionJpaRepository extends JpaRepository<ToolDefinitionDO, String> {
}
