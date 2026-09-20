package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.StateSnapshotDO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StateSnapshotJpaRepository extends JpaRepository<StateSnapshotDO, String> {

    List<StateSnapshotDO> findByTaskIdOrderByRoundAsc(String taskId);
}
