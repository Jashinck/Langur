package org.skylark.langur.common.cache;

import java.time.Duration;

/**
 * H4 - 分布式锁端口。
 * <p>以持有者标识（holder）实现可重入语义：同一 holder 重复获取视为成功；
 * 锁带 TTL 租约，持有者崩溃后自动过期释放，避免死锁（替代 DB 行级锁）。</p>
 * <p>内存实现为默认（单机），Redis（SETNX + 租约）实现用于多实例互斥；
 * Redis 不可用时实现方内部降级内存（P10）。</p>
 */
public interface DistributedLock {

    /**
     * 尝试获取锁。
     *
     * @param key    锁键
     * @param holder 持有者标识（同值可重入）
     * @param ttl    租约时长，到期自动释放
     * @return 获取成功（含重入）返回 {@code true}；被他人持有返回 {@code false}
     */
    boolean tryLock(String key, String holder, Duration ttl);

    /**
     * 释放锁；仅当当前持有者与 {@code holder} 一致时才删除（防误删他人锁）。
     */
    void unlock(String key, String holder);
}
