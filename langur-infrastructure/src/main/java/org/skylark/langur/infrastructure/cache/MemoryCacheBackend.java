package org.skylark.langur.infrastructure.cache;

import org.skylark.langur.common.cache.CacheBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H4 - 内存缓存后端（默认实现，{@code langur.cache.type=memory} 或缺省）。
 * <p>单机/测试兜底；亦作为 Redis 后端不可用时的降级目标（P10）。TTL 经惰性过期实现。</p>
 */
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "memory", matchIfMissing = true)
public class MemoryCacheBackend implements CacheBackend {

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Entry entry = store.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                return new Entry(null, 1L, expireAt(now, ttl));
            }
            return new Entry(existing.value, existing.counter + 1, existing.expireAtMillis);
        });
        return entry.counter;
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long now = System.currentTimeMillis();
        boolean[] inserted = {false};
        store.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                inserted[0] = true;
                return new Entry(value, 0L, expireAt(now, ttl));
            }
            return existing;
        });
        return inserted[0];
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, 0L, expireAt(System.currentTimeMillis(), ttl)));
    }

    @Override
    public void evict(String key) {
        store.remove(key);
    }

    private static long expireAt(long now, Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Long.MAX_VALUE : now + ttl.toMillis();
    }

    /** 不可变条目：value 用于 kv，counter 用于窗口计数；过期时间到点惰性清理。 */
    private static final class Entry {
        private final String value;
        private final long counter;
        private final long expireAtMillis;

        private Entry(String value, long counter, long expireAtMillis) {
            this.value = value;
            this.counter = counter;
            this.expireAtMillis = expireAtMillis;
        }

        private boolean isExpired(long now) {
            return now >= expireAtMillis;
        }
    }
}
