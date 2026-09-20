package org.skylark.langur.domain.harness.state;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S 组件聚合根 - 任务状态。
 * <p>状态机 + 乐观锁 version + 分布式锁持有者 + 每轮自动快照。</p>
 */
@Getter
public class TaskState {

    private final String taskId;
    private final List<StateSnapshot> snapshots;
    private TaskStateStatus status;
    private long version;
    private int currentRound;
    private String lockHolder;
    private Instant updatedAt;

    private TaskState(String taskId) {
        this.taskId = taskId;
        this.snapshots = new ArrayList<>();
        this.status = TaskStateStatus.INIT;
        this.version = 0L;
        this.currentRound = 0;
        this.updatedAt = Instant.now();
    }

    public static TaskState init(String taskId) {
        return new TaskState(taskId);
    }

    /**
     * 从持久化重建（T11）：由 JPA/Redis 分层存储还原聚合，保留 version 与快照序列。
     */
    public static TaskState restore(String taskId, TaskStateStatus status, long version, int currentRound,
                                    String lockHolder, Instant updatedAt, List<StateSnapshot> snapshots) {
        TaskState state = new TaskState(taskId);
        state.status = status != null ? status : TaskStateStatus.INIT;
        state.version = version;
        state.currentRound = currentRound;
        state.lockHolder = lockHolder;
        state.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        if (snapshots != null) {
            state.snapshots.addAll(snapshots);
        }
        return state;
    }

    public void transition(TaskStateStatus target) {
        this.status = target;
        bump();
    }

    /**
     * 分布式锁：仅当前持有者或空闲时可获取
     */
    public boolean acquireLock(String holder) {
        if (lockHolder != null && !lockHolder.equals(holder)) {
            return false;
        }
        this.lockHolder = holder;
        bump();
        return true;
    }

    public void releaseLock() {
        this.lockHolder = null;
        bump();
    }

    public void addSnapshot(StateSnapshot snapshot) {
        snapshots.add(snapshot);
        this.currentRound = snapshot.getRound();
        bump();
    }

    public StateSnapshot latestSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
    }

    /**
     * 断点续跑判定（T5）：存在快照且未进入终态即可从最近快照恢复。
     */
    public boolean isResumable() {
        return !snapshots.isEmpty()
                && status != TaskStateStatus.COMPLETED
                && status != TaskStateStatus.FAILED
                && status != TaskStateStatus.ROLLED_BACK;
    }

    private void bump() {
        this.version++;
        this.updatedAt = Instant.now();
    }
}
