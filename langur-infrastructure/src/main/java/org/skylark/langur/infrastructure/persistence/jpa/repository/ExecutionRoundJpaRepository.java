package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.ExecutionRoundDO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRoundJpaRepository extends JpaRepository<ExecutionRoundDO, Long> {

    List<ExecutionRoundDO> findByTaskIdOrderByRoundAsc(String taskId);
}
