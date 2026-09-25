package org.skylark.langur.api.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.cache.MemoryCacheBackend;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H4 - 缓存后端幂等存储测试：两实例共享同一后端即跨实例回放首次结果（模拟多实例 Redis 持久化）。
 */
class CacheIdempotencyStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CacheIdempotencyStore storeOn(MemoryCacheBackend backend) {
        return new CacheIdempotencyStore(backend, MAPPER, 1800L);
    }

    @Test
    void shouldReserveOnceAcrossSharedBackend() {
        MemoryCacheBackend shared = new MemoryCacheBackend();
        CacheIdempotencyStore a = storeOn(shared);
        CacheIdempotencyStore b = storeOn(shared);

        assertTrue(a.reserve("idem-1"));
        assertFalse(b.reserve("idem-1")); // 另一实例占位失败
        Optional<IdempotencyStore.Entry> found = b.find("idem-1");
        assertTrue(found.isPresent());
        assertFalse(found.get().completed()); // 处理中
    }

    @Test
    void shouldReplayFirstResponseAcrossInstances() {
        MemoryCacheBackend shared = new MemoryCacheBackend();
        CacheIdempotencyStore a = storeOn(shared);
        CacheIdempotencyStore b = storeOn(shared);

        assertTrue(a.reserve("idem-2"));
        byte[] body = "FIRST-RESULT".getBytes(StandardCharsets.UTF_8);
        a.complete("idem-2", new IdempotencyStore.StoredResponse(201, "application/json", body));

        Optional<IdempotencyStore.Entry> found = b.find("idem-2");
        assertTrue(found.isPresent());
        assertTrue(found.get().completed());
        IdempotencyStore.StoredResponse replayed = found.get().response();
        assertEquals(201, replayed.status());
        assertEquals("application/json", replayed.contentType());
        assertArrayEquals(body, replayed.body());
    }

    @Test
    void shouldReturnEmptyForUnknownKey() {
        assertTrue(storeOn(new MemoryCacheBackend()).find("missing").isEmpty());
    }
}
