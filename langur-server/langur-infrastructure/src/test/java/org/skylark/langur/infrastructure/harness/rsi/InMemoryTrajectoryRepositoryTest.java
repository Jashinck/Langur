package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.harness.rsi.Trajectory;
import org.skylark.langur.domain.harness.rsi.TrajectoryStep;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R0 验收 - 内存轨迹仓库：保存/加载/列举往返一致；缺失任务返回空；null 安全（不抛）。离线确定性。
 */
class InMemoryTrajectoryRepositoryTest {

    private final InMemoryTrajectoryRepository repository = new InMemoryTrajectoryRepository();

    private static Trajectory trajectory(String taskId) {
        return Trajectory.of(taskId, true, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "reason", 100L, 10L)), List.of());
    }

    @Test
    @DisplayName("保存后可按 taskId 加载，内容一致")
    void shouldRoundTripSavedTrajectory() {
        repository.save(trajectory("task-a"));

        Optional<Trajectory> loaded = repository.load("task-a");
        assertTrue(loaded.isPresent());
        assertEquals("task-a", loaded.get().taskId());
        assertEquals(1, loaded.get().steps().size());
    }

    @Test
    @DisplayName("未存任务返回空；listTaskIds 反映已存集合")
    void shouldReturnEmptyForMissingAndListSaved() {
        assertTrue(repository.load("nope").isEmpty());
        assertTrue(repository.listTaskIds().isEmpty());

        repository.save(trajectory("task-a"));
        repository.save(trajectory("task-b"));
        assertEquals(2, repository.listTaskIds().size());
        assertTrue(repository.listTaskIds().containsAll(List.of("task-a", "task-b")));
    }

    @Test
    @DisplayName("null 安全：save(null)/load(null) 不抛")
    void shouldBeNullSafe() {
        repository.save(null);
        assertFalse(repository.load(null).isPresent());
    }
}
