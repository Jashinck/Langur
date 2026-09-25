package org.skylark.langur.api.governance;

/**
 * 限流器端口（§13.3 / H4）。
 * <p>内存实现（{@link InMemoryRateLimiter}，单机默认）与缓存后端实现（{@code CacheRateLimiter}，
 * 多实例共享计数）由 {@code langur.cache.type} 条件装配。</p>
 */
public interface RateLimiter {

    /**
     * 尝试获取一个许可。
     *
     * @param key              限流维度（租户/用户/traceId）
     * @param permitsPerMinute 每分钟许可数；&lt;=0 表示不限流
     * @return true 放行；false 触发限流
     */
    boolean tryAcquire(String key, int permitsPerMinute);
}
