package org.skylark.langur.common.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * H4 - 分布式缓存后端端口（L2 热层原语）。
 * <p>提供带 TTL 的键值读写与原子计数，作为会话热状态 / 限流计数 / 幂等键持久化的统一底座。
 * 内存实现为默认（单机/测试），Redis 实现用于多实例共享；Redis 不可用时实现方内部降级内存（P10）。</p>
 */
public interface CacheBackend {

    /**
     * 原子自增并返回自增后的值；键首次创建时按 {@code ttl} 设置过期（固定窗口计数语义）。
     */
    long increment(String key, Duration ttl);

    /**
     * 仅当键不存在时写入并设置 TTL；成功写入返回 {@code true}，已存在返回 {@code false}。
     */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /** 读取键值；不存在或已过期返回 {@link Optional#empty()}。 */
    Optional<String> get(String key);

    /** 写入键值并设置 TTL（覆盖既有值）。 */
    void put(String key, String value, Duration ttl);

    /** 删除键（不存在时静默）。 */
    void evict(String key);
}
