package org.skylark.langur.api.governance;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等存储（§13.3）- 按幂等键记录请求处理状态与首次响应，重复请求直接回放首次结果。
 * <p>内存实现；两阶段：{@link #reserve} 占位（IN_PROGRESS）→ {@link #complete} 落首次响应（COMPLETED）。</p>
 */
@Component
public class InMemoryIdempotencyStore {

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 已存在（占位或已完成）返回其条目；否则占位并返回空。 */
    public Optional<Entry> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    /** 原子占位：成功占位返回 true（首次请求）；已被占用返回 false。 */
    public boolean reserve(String key) {
        return store.putIfAbsent(key, Entry.inProgress()) == null;
    }

    public void complete(String key, StoredResponse response) {
        store.put(key, Entry.completed(response));
    }

    public record StoredResponse(int status, String contentType, byte[] body) {
    }

    public record Entry(boolean completed, StoredResponse response) {
        static Entry inProgress() {
            return new Entry(false, null);
        }

        static Entry completed(StoredResponse response) {
            return new Entry(true, response);
        }
    }
}
