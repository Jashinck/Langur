package org.skylark.langur.domain.harness.context.vector;

import org.skylark.langur.domain.harness.context.MemoryEntry;
import org.skylark.langur.domain.harness.context.MemoryLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C 组件 L4 - 向量记忆服务（§12.2 Embedding 流水线 + cosine 召回 + Token 预算约束）。
 * <p>写入：文本经 {@link EmbeddingPort} 向量化后 UPSERT 入 {@link VectorStore}；
 * 召回：查询向量化 → cosine Top-K → 按 Token 预算截断，产出 L4 知识记忆供上下文装配消费。</p>
 * <p>纯领域实现（无 Spring 依赖），由 start 层装配。</p>
 */
public class VectorMemoryService {

    private final EmbeddingPort embeddingPort;
    private final VectorStore vectorStore;
    /** M3 重排端口（H2，可选）；为 null 时召回结果不重排。 */
    private final RerankPort rerankPort;

    public VectorMemoryService(EmbeddingPort embeddingPort, VectorStore vectorStore) {
        this(embeddingPort, vectorStore, null);
    }

    public VectorMemoryService(EmbeddingPort embeddingPort, VectorStore vectorStore, RerankPort rerankPort) {
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.rerankPort = rerankPort;
    }

    /**
     * 写入一条知识/长期记忆（向量化后 UPSERT）。
     */
    public void remember(String namespace, String id, String content, Map<String, Object> metadata) {
        if (content == null || content.isBlank()) {
            return;
        }
        float[] vector = embeddingPort.embed(content);
        vectorStore.upsert(VectorRecord.builder()
                .id(id)
                .namespace(namespace)
                .vector(vector)
                .content(content)
                .metadata(metadata != null ? metadata : new HashMap<>())
                .build());
    }

    /**
     * 语义召回（cosine Top-K），并按 Token 预算截断。
     *
     * @param tokenBudget Token 预算；{@code <=0} 表示不约束（仅受 topK 限制）
     * @return 按相似度降序、累计 Token 不超预算的记录
     */
    public List<VectorRecord> recall(String namespace, String query, int topK, long tokenBudget) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        float[] queryVector = embeddingPort.embed(query);
        List<VectorRecord> matches = vectorStore.search(namespace, queryVector, topK);
        // [M3] 重排去噪（H2）：装配 RerankPort 时对 cosine 召回结果重新排序
        if (rerankPort != null && !matches.isEmpty()) {
            matches = rerankPort.rerank(query, matches, topK);
        }
        List<VectorRecord> budgeted = new ArrayList<>();
        long consumed = 0L;
        for (VectorRecord record : matches) {
            long estimate = estimateTokens(record.getContent());
            if (tokenBudget > 0 && consumed + estimate > tokenBudget) {
                break;
            }
            consumed += estimate;
            budgeted.add(record);
        }
        return budgeted;
    }

    /**
     * 语义召回并转为 L4 知识记忆条目（受 Token 预算约束），供 {@code AgentContext.appendMemory} 消费。
     */
    public List<MemoryEntry> recallAsMemories(String namespace, String query, int topK, long tokenBudget) {
        return recall(namespace, query, topK, tokenBudget).stream()
                .map(record -> MemoryEntry.of(
                        MemoryLevel.L4_KNOWLEDGE, record.getContent(), estimateTokens(record.getContent())))
                .toList();
    }

    private long estimateTokens(String content) {
        return content == null ? 0L : content.length() / 4L + 1L;
    }
}
