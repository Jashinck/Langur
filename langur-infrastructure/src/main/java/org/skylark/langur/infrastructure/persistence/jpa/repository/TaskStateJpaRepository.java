package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.TaskStateDO;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskStateJpaRepository extends JpaRepository<TaskStateDO, String> {
}
