package org.skylark.langur.api.governance;

import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.cache.MemoryCacheBackend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H4 - 缓存后端限流器测试：两实例共享同一后端即共享配额（模拟多实例 Redis 计数），超阈值拒绝。
 */
class CacheRateLimiterTest {

    @Test
    void shouldShareQuotaAcrossInstancesViaSharedBackend() {
        MemoryCacheBackend shared = new MemoryCacheBackend();
        CacheRateLimiter instanceA = new CacheRateLimiter(shared);
        CacheRateLimiter instanceB = new CacheRateLimiter(shared);

        // permits=2：A 消费 1，B 消费 1（合计 2，均放行），第三次（任一实例）超阈值被拒
        assertTrue(instanceA.tryAcquire("tenant-a", 2));
        assertTrue(instanceB.tryAcquire("tenant-a", 2));
        assertFalse(instanceA.tryAcquire("tenant-a", 2));
        assertFalse(instanceB.tryAcquire("tenant-a", 2));
    }

    @Test
    void shouldNotLimitWhenPermitsNonPositive() {
        CacheRateLimiter limiter = new CacheRateLimiter(new MemoryCacheBackend());
        assertTrue(limiter.tryAcquire("tenant-a", 0));
        assertTrue(limiter.tryAcquire("tenant-a", -1));
    }

    @Test
    void shouldIsolateDifferentKeys() {
        MemoryCacheBackend shared = new MemoryCacheBackend();
        CacheRateLimiter limiter = new CacheRateLimiter(shared);
        assertTrue(limiter.tryAcquire("tenant-a", 1));
        assertFalse(limiter.tryAcquire("tenant-a", 1));
        assertTrue(limiter.tryAcquire("tenant-b", 1)); // 不同维度独立计数
    }
}
