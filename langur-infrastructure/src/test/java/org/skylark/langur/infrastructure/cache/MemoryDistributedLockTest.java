package org.skylark.langur.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H4 - 内存分布式锁测试（互斥 + 可重入 + 租约过期 + 持有者校验解锁）。
 */
class MemoryDistributedLockTest {

    private final MemoryDistributedLock lock = new MemoryDistributedLock();
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void shouldAcquireWhenFree() {
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
    }

    @Test
    void shouldRejectOtherHolderWhileLocked() {
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
        assertFalse(lock.tryLock("exec:1", "holder-b", TTL));
    }

    @Test
    void shouldBeReentrantForSameHolder() {
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
    }

    @Test
    void shouldReleaseOnlyByOwner() {
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
        lock.unlock("exec:1", "holder-b"); // 非持有者解锁无效
        assertFalse(lock.tryLock("exec:1", "holder-c", TTL));
        lock.unlock("exec:1", "holder-a"); // 持有者解锁
        assertTrue(lock.tryLock("exec:1", "holder-c", TTL));
    }

    @Test
    void shouldExpireLeaseAndAllowReacquire() throws InterruptedException {
        assertTrue(lock.tryLock("exec:1", "holder-a", Duration.ofMillis(1)));
        Thread.sleep(10);
        assertTrue(lock.tryLock("exec:1", "holder-b", TTL));
    }

    @Test
    void shouldRejectNullKeyOrHolder() {
        assertFalse(lock.tryLock(null, "holder-a", TTL));
        assertFalse(lock.tryLock("exec:1", null, TTL));
    }
}
