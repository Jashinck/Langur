package org.skylark.langur.api.governance;

import java.util.Optional;

/**
 * 幂等存储端口（§13.3 / H4）。
 * <p>两阶段：{@link #reserve} 占位（IN_PROGRESS）→ {@link #complete} 落首次响应（COMPLETED）；
 * 重复请求经 {@link #find} 回放首次结果。内存实现（单机默认）与缓存后端实现（多实例共享、TTL 持久化）
 * 由 {@code langur.cache.type} 条件装配。</p>
 */
public interface IdempotencyStore {

    /** 已存在（占位或已完成）返回其条目；否则返回空。 */
    Optional<Entry> find(String key);

    /** 原子占位：成功占位返回 true（首次请求）；已被占用返回 false。 */
    boolean reserve(String key);

    /** 落首次响应，标记完成。 */
    void complete(String key, StoredResponse response);

    /** 首次响应快照（状态 + 内容类型 + 原始字节）。 */
    record StoredResponse(int status, String contentType, byte[] body) {
    }

    /** 幂等条目：是否已完成 + 首次响应（未完成时为 null）。 */
    record Entry(boolean completed, StoredResponse response) {
        public static Entry inProgress() {
            return new Entry(false, null);
        }

        public static Entry completed(StoredResponse response) {
            return new Entry(true, response);
        }
    }
}
