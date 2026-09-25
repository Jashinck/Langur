package org.skylark.langur.api.governance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 限流器（§13.3）- 固定窗口计数，按 key 每分钟许可数限流。内存实现（单机默认，{@code langur.cache.type=memory}）。
 */
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取一个许可。
     *
     * @param key             限流维度（租户/用户/traceId）
     * @param permitsPerMinute 每分钟许可数；&lt;=0 表示不限流
     * @return true 放行；false 触发限流
     */
    @Override
    public boolean tryAcquire(String key, int permitsPerMinute) {
        if (permitsPerMinute <= 0 || key == null) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        AtomicReference<Window> ref = windows.computeIfAbsent(key, k -> new AtomicReference<>(new Window(minute, 0)));
        while (true) {
            Window current = ref.get();
            Window next = current.minute == minute
                    ? new Window(minute, current.count + 1)
                    : new Window(minute, 1);
            if (ref.compareAndSet(current, next)) {
                return next.count <= permitsPerMinute;
            }
        }
    }

    private record Window(long minute, int count) {
    }
}
