package org.skylark.langur.domain.harness.context.vector;

import java.util.List;

/**
 * C 组件 L4 - 向量存储端口（§12.2）。
 * <p>基础设施层提供内存实现（可测试默认）与 pgvector 实现（生产：{@code vector} 类型 + HNSW + cosine）。
 * 领域层仅依赖本接口，遵循依赖倒置（P3）。</p>
 */
public interface VectorStore {

    /**
     * 写入或更新一条向量记录（UPSERT 语义，按 namespace + id 唯一）。
     */
    void upsert(VectorRecord record);

    /**
     * cosine 相似度召回。
     *
     * @param namespace   多租户/业务域命名空间
     * @param queryVector 查询向量
     * @param topK        返回上限
     * @return 按相似度降序排列、回填 {@code score} 的记录（至多 topK 条）
     */
    List<VectorRecord> search(String namespace, float[] queryVector, int topK);

    // —— v2.1 增量（H14.1，均 default：既有实现零改动即编译通过，P5）——

    /**
     * 批量写入（H14.1）：默认逐条转调 {@link #upsert}；ES/Milvus 可覆写为批量 API。
     * namespace 为冗余便捷参数（记录自带 namespace），供批量路由/校验使用。
     */
    default void upsertAll(String namespace, List<VectorRecord> records) {
        if (records != null) {
            records.forEach(this::upsert);
        }
    }

    /**
     * 按 namespace + id 删除（H14.1）：默认不支持；具备删除能力的 store 覆写。
     */
    default void delete(String namespace, String id) {
        throw new UnsupportedOperationException("delete not supported by " + getClass().getSimpleName());
    }

    /**
     * 结构化检索（H14.1）：默认降级为既有 {@code search(ns, vector, topK)}（忽略 filter/minScore）；
     * 支持过滤的 store 覆写。namespace 由实现强制过滤（越权红线）。
     */
    default List<VectorRecord> search(SearchQuery query) {
        return search(query.getNamespace(), query.getQueryVector(), query.getTopK());
    }

    /**
     * 原生混合检索能力位（H14.1）：{@code VectorMemoryService.recall} 据此分支——
     * true 且 {@code hybrid.enabled} 时走 {@link #hybridSearch} 原生下推，否则应用侧兜底。
     */
    default boolean supportsHybrid() {
        return false;
    }

    /**
     * 原生混合检索（H14.1）：服务端 BM25/稀疏 + ANN 融合（ES retriever.rrf / Milvus hybrid_search）；
     * 默认不支持，仅 {@link #supportsHybrid()} 为 true 的实现覆写。
     */
    default List<VectorRecord> hybridSearch(HybridQuery query) {
        throw new UnsupportedOperationException("native hybrid not supported by " + getClass().getSimpleName());
    }
}
