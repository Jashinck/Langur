package org.skylark.langur.infrastructure.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.common.cache.DistributedLock;
import org.skylark.langur.domain.harness.execution.ExecutionLoopService;
import org.skylark.langur.domain.harness.execution.ExecutionStatus;
import org.skylark.langur.domain.harness.execution.ExecutionTask;
import org.skylark.langur.domain.harness.execution.RuntimeParadigm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H4 - 分布式锁执行装饰器测试：并发重入拒绝、透明委派、异常释放锁。
 */
class LockingExecutionLoopServiceTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private static ExecutionTask task() {
        return ExecutionTask.create("agent-1", "biz", RuntimeParadigm.REACT, null);
    }

    /** 可控锁桩：记录 tryLock/unlock 调用，按预设结果放行/拒绝。 */
    private static final class StubLock implements DistributedLock {
        boolean allow = true;
        final List<String> unlocked = new ArrayList<>();
        int tryLockCalls = 0;

        @Override
        public boolean tryLock(String key, String holder, Duration ttl) {
            tryLockCalls++;
            return allow;
        }

        @Override
        public void unlock(String key, String holder) {
            unlocked.add(key);
        }
    }

    /** 委派桩：标记完成并返回同一任务，记录是否被调用。 */
    private static final class StubDelegate implements ExecutionLoopService {
        boolean called = false;
        boolean throwOnExecute = false;

        @Override
        public ExecutionTask execute(ExecutionTask task,
                                     org.skylark.langur.domain.model.agent.Agent agent,
                                     org.skylark.langur.domain.model.plan.Plan plan) {
            called = true;
            if (throwOnExecute) {
                throw new RuntimeException("boom");
            }
            task.complete();
            return task;
        }
    }

    @Test
    void shouldTerminateWhenLockDenied() {
        StubLock lock = new StubLock();
        lock.allow = false;
        StubDelegate delegate = new StubDelegate();
        LockingExecutionLoopService service = new LockingExecutionLoopService(delegate, lock, TTL);

        ExecutionTask result = service.execute(task(), null, null);

        assertEquals(ExecutionStatus.TERMINATED, result.getStatus());
        assertTrue(result.getTerminateReason().contains("distributed lock"));
        assertFalse(delegate.called);
        assertTrue(lock.unlocked.isEmpty()); // 未获取到锁不应释放
    }

    @Test
    void shouldDelegateAndUnlockWhenLockAcquired() {
        StubLock lock = new StubLock();
        StubDelegate delegate = new StubDelegate();
        LockingExecutionLoopService service = new LockingExecutionLoopService(delegate, lock, TTL);

        ExecutionTask input = task();
        ExecutionTask result = service.execute(input, null, null);

        assertTrue(delegate.called);
        assertSame(input, result);
        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(1, lock.unlocked.size());
    }

    @Test
    void shouldUnlockEvenWhenDelegateThrows() {
        StubLock lock = new StubLock();
        StubDelegate delegate = new StubDelegate();
        delegate.throwOnExecute = true;
        LockingExecutionLoopService service = new LockingExecutionLoopService(delegate, lock, TTL);

        try {
            service.execute(task(), null, null);
        } catch (RuntimeException ignored) {
            // 期望委派异常向上抛出
        }
        assertEquals(1, lock.unlocked.size()); // finally 释放
    }
}
