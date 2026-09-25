package org.skylark.langur.infrastructure.harness.context.vector;

import org.skylark.langur.domain.harness.context.vector.SearchQuery;
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
 * <p>H14.1：覆写 {@code search(SearchQuery)} 支持 metadata 过滤 + minScore 阈值，
 * 及 {@code delete}；{@code supportsHybrid()=false} 保持应用侧兜底路径不变。</p>
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
    public void delete(String namespace, String id) {
        Map<String, VectorRecord> records = store.get(namespace);
        if (records != null) {
            records.remove(id);
        }
    }

    @Override
    public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
        return search(SearchQuery.of(namespace, queryVector, topK));
    }

    @Override
    public List<VectorRecord> search(SearchQuery query) {
        if (query.getTopK() <= 0) {
            return List.of();
        }
        Map<String, VectorRecord> records = store.get(query.getNamespace());
        if (records == null) {
            return List.of();
        }
        float[] queryVector = query.getQueryVector();
        // minScore<=0 视为无阈值（保持既有 search(ns,vec,topK) 行为完全不变；cosine 可为负）
        double threshold = query.getMinScore();
        return records.values().stream()
                .filter(record -> query.getFilter().matches(record.getMetadata()))
                .map(record -> record.toBuilder()
                        .score(VectorMath.cosine(queryVector, record.getVector()))
                        .build())
                .filter(record -> threshold <= 0.0 || record.getScore() >= threshold)
                .sorted(Comparator.comparingDouble(VectorRecord::getScore).reversed())
                .limit(query.getTopK())
                .toList();
    }
}
