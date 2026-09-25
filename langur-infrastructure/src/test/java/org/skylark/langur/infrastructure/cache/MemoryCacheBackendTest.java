package org.skylark.langur.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H4 - 内存缓存后端测试（L2 热层原语 + 固定窗口计数）。
 */
class MemoryCacheBackendTest {

    private final MemoryCacheBackend backend = new MemoryCacheBackend();

    @Test
    void shouldIncrementWithinSameWindow() {
        assertEquals(1L, backend.increment("k", Duration.ofMinutes(1)));
        assertEquals(2L, backend.increment("k", Duration.ofMinutes(1)));
        assertEquals(3L, backend.increment("k", Duration.ofMinutes(1)));
    }

    @Test
    void shouldSetIfAbsentOnlyOnce() {
        assertTrue(backend.setIfAbsent("k", "v1", Duration.ofMinutes(1)));
        assertFalse(backend.setIfAbsent("k", "v2", Duration.ofMinutes(1)));
        assertEquals("v1", backend.get("k").orElse(null));
    }

    @Test
    void shouldPutGetEvict() {
        backend.put("k", "v", Duration.ofMinutes(1));
        assertEquals(Optional.of("v"), backend.get("k"));
        backend.evict("k");
        assertTrue(backend.get("k").isEmpty());
    }

    @Test
    void shouldExpireEntriesAfterTtl() throws InterruptedException {
        backend.put("k", "v", Duration.ofMillis(1));
        Thread.sleep(10);
        assertTrue(backend.get("k").isEmpty());
    }

    @Test
    void shouldResetWindowAfterExpiry() throws InterruptedException {
        assertEquals(1L, backend.increment("k", Duration.ofMillis(1)));
        Thread.sleep(10);
        assertEquals(1L, backend.increment("k", Duration.ofMinutes(1)));
    }
}
