package org.skylark.langur.infrastructure.cache;

import org.skylark.langur.common.cache.DistributedLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H4 - 内存分布式锁（默认实现，{@code langur.cache.type=memory} 或缺省）。
 * <p>单机互斥 + 可重入（同 holder）+ TTL 租约自动过期；亦作为 Redis 锁不可用时的降级目标（P10）。</p>
 */
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "memory", matchIfMissing = true)
public class MemoryDistributedLock implements DistributedLock {

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String key, String holder, Duration ttl) {
        if (key == null || holder == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expireAt = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Long.MAX_VALUE : now + ttl.toMillis();
        boolean[] acquired = {false};
        leases.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(now) || existing.holder.equals(holder)) {
                acquired[0] = true;
                return new Lease(holder, expireAt);
            }
            return existing;
        });
        return acquired[0];
    }

    @Override
    public void unlock(String key, String holder) {
        if (key == null || holder == null) {
            return;
        }
        leases.computeIfPresent(key, (k, existing) -> existing.holder.equals(holder) ? null : existing);
    }

    private static final class Lease {
        private final String holder;
        private final long expireAtMillis;

        private Lease(String holder, long expireAtMillis) {
            this.holder = holder;
            this.expireAtMillis = expireAtMillis;
        }

        private boolean isExpired(long now) {
            return now >= expireAtMillis;
        }
    }
}
