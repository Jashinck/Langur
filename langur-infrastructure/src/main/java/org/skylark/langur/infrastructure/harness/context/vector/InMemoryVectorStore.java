package org.skylark.langur.infrastructure.harness.context.vector;

import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * C 组件 L4 - 内存向量存储（可测试默认实现，§12.2）。
 * <p>按 namespace 隔离，暴力 cosine Top-K 召回；生产可切换 pgvector 实现
 * （{@code langur.vector.store=pgvector}）。</p>
 */
@Component
@ConditionalOnProperty(name = "langur.vector.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final ConcurrentMap<String, ConcurrentMap<String, VectorRecord>> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(VectorRecord record) {
        store.computeIfAbsent(record.getNamespace(), k -> new ConcurrentHashMap<>())
                .put(record.getId(), record);
    }

    @Override
    public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        Map<String, VectorRecord> records = store.get(namespace);
        if (records == null) {
            return List.of();
        }
        return records.values().stream()
                .map(record -> record.toBuilder()
                        .score(VectorMath.cosine(queryVector, record.getVector()))
                        .build())
                .sorted(Comparator.comparingDouble(VectorRecord::getScore).reversed())
                .limit(topK)
                .toList();
    }
}
