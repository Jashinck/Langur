package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.ToolInvocationDO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolInvocationJpaRepository extends JpaRepository<ToolInvocationDO, Long> {

    List<ToolInvocationDO> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
