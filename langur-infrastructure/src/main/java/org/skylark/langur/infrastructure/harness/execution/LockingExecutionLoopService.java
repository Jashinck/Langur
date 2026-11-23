package org.skylark.langur.infrastructure.harness.execution;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.common.cache.DistributedLock;
import org.skylark.langur.domain.harness.execution.ExecutionLoopService;
import org.skylark.langur.domain.harness.execution.ExecutionTask;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.Plan;

import java.time.Duration;

/**
 * H4 - 分布式锁执行装饰器。
 * <p>在 E 组件统一入口外层套一层跨实例互斥：执行前按 taskId 获取分布式锁（holder=traceId），
 * 获取失败即判定并发重入并终止任务；执行结束（含异常）释放锁。替代原 DB 行级锁的跨实例语义，
 * 与聚合内 {@code TaskState.acquireLock} 互补（实例级 + 聚合级双重保护）。</p>
 * <p>锁后端由 {@code langur.cache.type} 决定（memory 单机 / redis 多实例），透明委派不改变范式分发行为。</p>
 */
@Slf4j
public class LockingExecutionLoopService implements ExecutionLoopService {

    private final ExecutionLoopService delegate;
    private final DistributedLock lock;
    private final Duration lockTtl;

    public LockingExecutionLoopService(ExecutionLoopService delegate, DistributedLock lock, Duration lockTtl) {
        this.delegate = delegate;
        this.lock = lock;
        this.lockTtl = lockTtl;
    }

    @Override
    public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
        String lockKey = "exec:" + task.getTaskId();
        String holder = task.getTraceId() != null ? task.getTraceId() : task.getTaskId();
        if (!lock.tryLock(lockKey, holder, lockTtl)) {
            log.warn("[H4] concurrent execution rejected by distributed lock: task={} holder={}",
                    task.getTaskId(), holder);
            task.terminate("Concurrent execution rejected: distributed lock held by another holder");
            return task;
        }
        try {
            return delegate.execute(task, agent, plan);
        } finally {
            lock.unlock(lockKey, holder);
        }
    }
}
