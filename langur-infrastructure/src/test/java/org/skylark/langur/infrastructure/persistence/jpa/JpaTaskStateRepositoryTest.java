package org.skylark.langur.infrastructure.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.infrastructure.persistence.jpa.entity.StateSnapshotDO;
import org.skylark.langur.infrastructure.persistence.jpa.entity.TaskStateDO;
import org.skylark.langur.infrastructure.persistence.jpa.impl.JpaTaskStateRepository;
import org.skylark.langur.infrastructure.persistence.jpa.repository.StateSnapshotJpaRepository;
import org.skylark.langur.infrastructure.persistence.jpa.repository.TaskStateJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "langur.repository.type=jpa"
})
@Import(JpaTaskStateRepositoryTest.TestConfig.class)
class JpaTaskStateRepositoryTest {

    @Autowired
    private TaskStateJpaRepository taskStateJpaRepository;
    @Autowired
    private StateSnapshotJpaRepository snapshotJpaRepository;
    @Autowired
    private JsonValueMapper jsonValueMapper;
    @Autowired
    private TestEntityManager entityManager;

    private TaskStateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JpaTaskStateRepository(taskStateJpaRepository, snapshotJpaRepository, jsonValueMapper);
    }

    @Test
    void shouldPersistAndRestoreTaskStateWithSnapshots() {
        TaskState state = TaskState.init("task-1");
        state.transition(TaskStateStatus.RUNNING);
        state.addSnapshot(StateSnapshot.of("task-1", 1, Map.of("step", "a")));
        state.addSnapshot(StateSnapshot.of("task-1", 2, Map.of("step", "b")));
        repository.save(state);

        TaskState loaded = repository.findById("task-1").orElseThrow();

        assertEquals(TaskStateStatus.RUNNING, loaded.getStatus());
        assertEquals(2, loaded.getCurrentRound());
        assertEquals(2, loaded.getSnapshots().size());
        assertEquals("a", loaded.getSnapshots().get(0).getPayload().get("step"));
        assertEquals("b", loaded.getSnapshots().get(1).getPayload().get("step"));
        assertTrue(loaded.isResumable());
    }

    @Test
    void shouldThrowObjectOptimisticLockingFailureOnStaleVersion() {
        TaskStateDO first = new TaskStateDO();
        first.setTaskId("lock-1");
        first.setStatus(TaskStateStatus.INIT.name());
        first.setCurrentRound(0);
        first.setUpdatedAt(Instant.now());
        taskStateJpaRepository.saveAndFlush(first);
        Long staleVersion = first.getVersion();

        // 推进 DB 版本（version + 1）
        TaskStateDO managed = taskStateJpaRepository.findById("lock-1").orElseThrow();
        managed.setStatus(TaskStateStatus.RUNNING.name());
        taskStateJpaRepository.saveAndFlush(managed);

        // 携带过期版本的游离实体 -> 合并刷库时触发乐观锁冲突
        TaskStateDO stale = new TaskStateDO();
        stale.setTaskId("lock-1");
        stale.setStatus(TaskStateStatus.COMPLETED.name());
        stale.setCurrentRound(0);
        stale.setVersion(staleVersion);
        stale.setUpdatedAt(Instant.now());

        entityManager.clear();

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> taskStateJpaRepository.saveAndFlush(stale));
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {TaskStateDO.class, StateSnapshotDO.class})
    @EnableJpaRepositories(basePackageClasses = {TaskStateJpaRepository.class, StateSnapshotJpaRepository.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JsonValueMapper jsonValueMapper(ObjectMapper objectMapper) {
            return new JsonValueMapper(objectMapper);
        }
    }
}
