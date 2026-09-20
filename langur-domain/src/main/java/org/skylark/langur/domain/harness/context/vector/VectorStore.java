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
}
