package org.skylark.langur.api.governance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等存储（§13.3）- 按幂等键记录请求处理状态与首次响应，重复请求直接回放首次结果。
 * <p>内存实现（单机默认，{@code langur.cache.type=memory}）；两阶段：
 * {@link #reserve} 占位（IN_PROGRESS）→ {@link #complete} 落首次响应（COMPLETED）。</p>
 */
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Entry> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public boolean reserve(String key) {
        return store.putIfAbsent(key, Entry.inProgress()) == null;
    }

    @Override
    public void complete(String key, StoredResponse response) {
        store.put(key, Entry.completed(response));
    }
}
