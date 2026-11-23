package org.skylark.langur.api.governance;

import org.skylark.langur.common.cache.CacheBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 限流器（§13.3 / H4）- 缓存后端固定窗口计数（{@code langur.cache.type=redis} 时装配）。
 * <p>以"分钟窗口键 + 原子自增 + 窗口 TTL"实现跨实例共享计数：多实例命中同一后端即共享配额，
 * 超阈值返回 false（治理链据此 429）。后端不可用时由其内部降级内存（P10）。</p>
 */
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "redis")
public class CacheRateLimiter implements RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final CacheBackend cacheBackend;

    public CacheRateLimiter(CacheBackend cacheBackend) {
        this.cacheBackend = cacheBackend;
    }

    @Override
    public boolean tryAcquire(String key, int permitsPerMinute) {
        if (permitsPerMinute <= 0 || key == null) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        long count = cacheBackend.increment("ratelimit:" + key + ":" + minute, WINDOW);
        return count <= permitsPerMinute;
    }
}
