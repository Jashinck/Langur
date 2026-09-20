package org.skylark.langur.infrastructure.persistence.jpa.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.infrastructure.persistence.jpa.JsonValueMapper;
import org.skylark.langur.infrastructure.persistence.jpa.entity.StateSnapshotDO;
import org.skylark.langur.infrastructure.persistence.jpa.entity.TaskStateDO;
import org.skylark.langur.infrastructure.persistence.jpa.repository.StateSnapshotJpaRepository;
import org.skylark.langur.infrastructure.persistence.jpa.repository.TaskStateJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * S 组件 - MySQL 状态存储实现（L3 归档层，T11）。
 * <p>映射 {@link TaskState} 聚合到 t_task_state（含 @Version 乐观锁）+ t_state_snapshot；
 * 快照 payload 以 JSON 持久化，支持断点续跑重建。</p>
 */
@Repository
@ConditionalOnProperty(name = "langur.repository.type", havingValue = "jpa")
public class JpaTaskStateRepository implements TaskStateRepository {

    private final TaskStateJpaRepository taskStateJpaRepository;
    private final StateSnapshotJpaRepository snapshotJpaRepository;
    private final JsonValueMapper jsonValueMapper;

    public JpaTaskStateRepository(TaskStateJpaRepository taskStateJpaRepository,
                                  StateSnapshotJpaRepository snapshotJpaRepository,
                                  JsonValueMapper jsonValueMapper) {
        this.taskStateJpaRepository = taskStateJpaRepository;
        this.snapshotJpaRepository = snapshotJpaRepository;
        this.jsonValueMapper = jsonValueMapper;
    }

    @Override
    public void save(TaskState state) {
        // 复用受管实体：JPA @Version 由 Hibernate 权威管理，save() 不手动设置 version，避免与领域 version 冲突。
        TaskStateDO entity = taskStateJpaRepository.findById(state.getTaskId())
                .orElseGet(TaskStateDO::new);
        entity.setTaskId(state.getTaskId());
        entity.setStatus(state.getStatus().name());
        entity.setCurrentRound(state.getCurrentRound());
        entity.setLockHolder(state.getLockHolder());
        entity.setUpdatedAt(state.getUpdatedAt());
        taskStateJpaRepository.save(entity);

        for (StateSnapshot snapshot : state.getSnapshots()) {
            if (snapshotJpaRepository.existsById(snapshot.getSnapshotId())) {
                continue;
            }
            StateSnapshotDO snapshotDO = new StateSnapshotDO();
            snapshotDO.setSnapshotId(snapshot.getSnapshotId());
            snapshotDO.setTaskId(snapshot.getTaskId());
            snapshotDO.setRound(snapshot.getRound());
            snapshotDO.setPayload(jsonValueMapper.write(snapshot.getPayload()));
            snapshotDO.setCreatedAt(snapshot.getCreatedAt());
            snapshotJpaRepository.save(snapshotDO);
        }
    }

    @Override
    public Optional<TaskState> findById(String taskId) {
        return taskStateJpaRepository.findById(taskId).map(entity -> {
            List<StateSnapshotDO> snapshotDOs = snapshotJpaRepository.findByTaskIdOrderByRoundAsc(taskId);
            List<StateSnapshot> snapshots = new ArrayList<>();
            for (StateSnapshotDO snapshotDO : snapshotDOs) {
                snapshots.add(StateSnapshot.builder()
                        .snapshotId(snapshotDO.getSnapshotId())
                        .taskId(snapshotDO.getTaskId())
                        .round(snapshotDO.getRound())
                        .payload(jsonValueMapper.read(snapshotDO.getPayload(),
                                new TypeReference<HashMap<String, Object>>() {}, new HashMap<>()))
                        .createdAt(snapshotDO.getCreatedAt())
                        .build());
            }
            return TaskState.restore(
                    entity.getTaskId(),
                    TaskStateStatus.valueOf(entity.getStatus()),
                    entity.getVersion() != null ? entity.getVersion() : 0L,
                    entity.getCurrentRound(),
                    entity.getLockHolder(),
                    entity.getUpdatedAt(),
                    snapshots);
        });
    }
}
