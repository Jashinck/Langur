package org.skylark.langur.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.common.cache.CacheBackend;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * H4 - Redis 缓存后端（{@code langur.cache.type=redis} 时装配）。
 * <p>承载 L2 会话热状态 / 限流计数 / 幂等键的多实例共享持久化。任一操作在 Redis 不可用
 * （未配置连接或抛异常）时静默降级到内存后端，保证主链路不中断（P10）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "redis")
public class RedisCacheBackend implements CacheBackend {

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final String keyPrefix;
    private final MemoryCacheBackend fallback = new MemoryCacheBackend();

    public RedisCacheBackend(ObjectProvider<StringRedisTemplate> templateProvider, CacheProperties properties) {
        this.templateProvider = templateProvider;
        this.keyPrefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix() : "";
    }

    private StringRedisTemplate template() {
        return templateProvider.getIfAvailable();
    }

    private String k(String key) {
        return keyPrefix + key;
    }

    @Override
    public long increment(String key, Duration ttl) {
        StringRedisTemplate redis = template();
        if (redis == null) {
            return fallback.increment(key, ttl);
        }
        try {
            Long value = redis.opsForValue().increment(k(key));
            if (value != null && value == 1L && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(k(key), ttl);
            }
            return value != null ? value : fallback.increment(key, ttl);
        } catch (RuntimeException e) {
            log.debug("[H4] Redis increment failed, degrade to memory: {}", e.getMessage());
            return fallback.increment(key, ttl);
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        StringRedisTemplate redis = template();
        if (redis == null) {
            return fallback.setIfAbsent(key, value, ttl);
        }
        try {
            Boolean ok = ttl != null && !ttl.isZero() && !ttl.isNegative()
                    ? redis.opsForValue().setIfAbsent(k(key), value, ttl)
                    : redis.opsForValue().setIfAbsent(k(key), value);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.debug("[H4] Redis setIfAbsent failed, degrade to memory: {}", e.getMessage());
            return fallback.setIfAbsent(key, value, ttl);
        }
    }

    @Override
    public Optional<String> get(String key) {
        StringRedisTemplate redis = template();
        if (redis == null) {
            return fallback.get(key);
        }
        try {
            return Optional.ofNullable(redis.opsForValue().get(k(key)));
        } catch (RuntimeException e) {
            log.debug("[H4] Redis get failed, degrade to memory: {}", e.getMessage());
            return fallback.get(key);
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        StringRedisTemplate redis = template();
        if (redis == null) {
            fallback.put(key, value, ttl);
            return;
        }
        try {
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.opsForValue().set(k(key), value, ttl);
            } else {
                redis.opsForValue().set(k(key), value);
            }
        } catch (RuntimeException e) {
            log.debug("[H4] Redis put failed, degrade to memory: {}", e.getMessage());
            fallback.put(key, value, ttl);
        }
    }

    @Override
    public void evict(String key) {
        StringRedisTemplate redis = template();
        if (redis == null) {
            fallback.evict(key);
            return;
        }
        try {
            redis.delete(k(key));
        } catch (RuntimeException e) {
            log.debug("[H4] Redis evict failed, degrade to memory: {}", e.getMessage());
            fallback.evict(key);
        }
    }
}
