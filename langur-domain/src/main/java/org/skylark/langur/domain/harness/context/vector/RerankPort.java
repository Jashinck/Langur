package org.skylark.langur.domain.harness.context.vector;

import java.util.List;

/**
 * C 组件 L4 - 重排端口（M3，H2）。
 * <p>向量召回（cosine Top-K）后可选的重排去噪：基础设施层可提供 LLM/交叉编码/混合打分实现，
 * 领域层仅依赖本接口对候选集重新排序，遵循依赖倒置（P3）。缺省不装配即不重排。</p>
 */
public interface RerankPort {

    /**
     * 对候选记录按与查询的相关性重排。
     *
     * @param query      原始查询文本
     * @param candidates 向量召回的候选（已回填 cosine {@code score}）
     * @param topK       重排后保留上限；{@code <=0} 表示不额外截断（沿用候选规模）
     * @return 重排后的记录（回填新的相关性 {@code score}），按相关性降序
     */
    List<VectorRecord> rerank(String query, List<VectorRecord> candidates, int topK);
}
