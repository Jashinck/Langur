package org.skylark.langur.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.common.cache.DistributedLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * H4 - Redis 分布式锁（{@code langur.cache.type=redis} 时装配）。
 * <p>SETNX + TTL 租约实现多实例互斥；同 holder 可重入并续租；解锁经 Lua 脚本比较持有者后原子删除，
 * 防误删他人锁。Redis 不可用时静默降级内存锁，保证主链路不中断（P10）。</p>
 * <p>租约到期自动释放，规避持有者崩溃导致的死锁（watchdog 主动续期为后续增强项 DD2）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "redis")
public class RedisDistributedLock implements DistributedLock {

    /** 比较持有者一致才删除，保证解锁原子性。 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final String keyPrefix;
    private final MemoryDistributedLock fallback = new MemoryDistributedLock();

    public RedisDistributedLock(ObjectProvider<StringRedisTemplate> templateProvider, CacheProperties properties) {
        this.templateProvider = templateProvider;
        this.keyPrefix = (properties.getKeyPrefix() != null ? properties.getKeyPrefix() : "") + "lock:";
    }

    private String k(String key) {
        return keyPrefix + key;
    }

    @Override
    public boolean tryLock(String key, String holder, Duration ttl) {
        if (key == null || holder == null) {
            return false;
        }
        StringRedisTemplate redis = templateProvider.getIfAvailable();
        if (redis == null) {
            return fallback.tryLock(key, holder, ttl);
        }
        String redisKey = k(key);
        try {
            Boolean acquired = ttl != null && !ttl.isZero() && !ttl.isNegative()
                    ? redis.opsForValue().setIfAbsent(redisKey, holder, ttl)
                    : redis.opsForValue().setIfAbsent(redisKey, holder);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            // 可重入：已被同一 holder 持有则续租并放行
            String current = redis.opsForValue().get(redisKey);
            if (holder.equals(current)) {
                if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                    redis.expire(redisKey, ttl);
                }
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.debug("[H4] Redis tryLock failed, degrade to memory lock: {}", e.getMessage());
            return fallback.tryLock(key, holder, ttl);
        }
    }

    @Override
    public void unlock(String key, String holder) {
        if (key == null || holder == null) {
            return;
        }
        StringRedisTemplate redis = templateProvider.getIfAvailable();
        if (redis == null) {
            fallback.unlock(key, holder);
            return;
        }
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(k(key)), holder);
        } catch (RuntimeException e) {
            log.debug("[H4] Redis unlock failed, degrade to memory lock: {}", e.getMessage());
            fallback.unlock(key, holder);
        }
    }
}
