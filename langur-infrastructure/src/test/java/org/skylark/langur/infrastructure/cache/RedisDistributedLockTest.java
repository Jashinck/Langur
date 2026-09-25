package org.skylark.langur.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H4 - Redis 分布式锁 P10 降级测试：模板缺失或抛异常时降级内存锁，互斥语义不丢。
 */
class RedisDistributedLockTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private static CacheProperties props() {
        CacheProperties p = new CacheProperties();
        p.setKeyPrefix("langur:");
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerReturning(StringRedisTemplate template) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    @Test
    void shouldDegradeToMemoryLockWhenTemplateUnavailable() {
        RedisDistributedLock lock = new RedisDistributedLock(providerReturning(null), props());
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
        assertFalse(lock.tryLock("exec:1", "holder-b", TTL));
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL)); // 可重入
        lock.unlock("exec:1", "holder-a");
        assertTrue(lock.tryLock("exec:1", "holder-b", TTL));
    }

    @Test
    void shouldDegradeToMemoryLockWhenRedisThrows() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        RedisDistributedLock lock = new RedisDistributedLock(providerReturning(template), props());
        // 抛异常被捕获并降级内存锁：首次获取成功，重入成功，异holder被拒
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
        assertTrue(lock.tryLock("exec:1", "holder-a", TTL));
        assertFalse(lock.tryLock("exec:1", "holder-b", TTL));
        lock.unlock("exec:1", "holder-a"); // 不应抛出
    }
}
