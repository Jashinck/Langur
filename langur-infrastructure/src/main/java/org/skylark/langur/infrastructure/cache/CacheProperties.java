package org.skylark.langur.infrastructure.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * H4 - 缓存/分布式锁配置（{@code langur.cache.*}）。
 * <p>{@code type=memory}（默认，单机/测试）| {@code redis}（多实例共享，Redis 不可用自动降级内存 P10）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.cache")
public class CacheProperties {

    /** 缓存后端类型：memory（默认）| redis。 */
    private String type = "memory";

    /** L2 会话热状态 / 幂等键默认 TTL（秒）。 */
    private long ttlSeconds = 1800L;

    /** 分布式锁租约时长（秒），到期自动释放防死锁。 */
    private long lockTtlSeconds = 30L;

    /** Redis 键前缀（命名空间隔离）。 */
    private String keyPrefix = "langur:";
}
