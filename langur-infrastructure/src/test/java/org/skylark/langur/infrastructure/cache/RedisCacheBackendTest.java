package org.skylark.langur.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H4 - Redis 缓存后端 P10 降级测试：模板缺失或抛异常时静默降级内存，不中断主链路。
 */
class RedisCacheBackendTest {

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
    void shouldDegradeToMemoryWhenTemplateUnavailable() {
        RedisCacheBackend backend = new RedisCacheBackend(providerReturning(null), props());
        backend.put("k", "v", Duration.ofMinutes(1));
        assertEquals("v", backend.get("k").orElse(null));
        assertEquals(1L, backend.increment("c", Duration.ofMinutes(1)));
        assertEquals(2L, backend.increment("c", Duration.ofMinutes(1)));
        assertTrue(backend.setIfAbsent("s", "1", Duration.ofMinutes(1)));
        assertFalse(backend.setIfAbsent("s", "2", Duration.ofMinutes(1)));
    }

    @Test
    void shouldDegradeToMemoryWhenRedisThrows() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        when(template.delete(anyString())).thenThrow(new RuntimeException("connection refused"));
        RedisCacheBackend backend = new RedisCacheBackend(providerReturning(template), props());

        // 所有操作均应捕获异常并降级内存，不向外抛
        backend.put("k", "v", Duration.ofMinutes(1));
        assertEquals("v", backend.get("k").orElse(null));
        assertEquals(1L, backend.increment("c", Duration.ofMinutes(1)));
        assertTrue(backend.setIfAbsent("s", "1", Duration.ofMinutes(1)));
        backend.evict("k");
        assertTrue(backend.get("k").isEmpty());
    }
}
