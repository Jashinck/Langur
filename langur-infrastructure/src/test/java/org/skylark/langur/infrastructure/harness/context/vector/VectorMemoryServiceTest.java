package org.skylark.langur.infrastructure.harness.context.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.MemoryEntry;
import org.skylark.langur.domain.harness.context.MemoryLevel;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14 - C 组件 L4 向量记忆：写入向量化 + cosine Top-K 召回 + Token 预算 + namespace 隔离。
 */
class VectorMemoryServiceTest {

    private static final String NS = "tenant-a";

    private VectorMemoryService service;

    @BeforeEach
    void setUp() {
        service = new VectorMemoryService(new LexicalEmbeddingPort(), new InMemoryVectorStore());
    }

    @Test
    @DisplayName("语义召回按相似度降序返回最相关记录")
    void recallRanksBySimilarity() {
        service.remember(NS, "k1", "how to reset the database password", Map.of());
        service.remember(NS, "k2", "recipe for chocolate cake", Map.of());
        service.remember(NS, "k3", "reset the database connection pool", Map.of());

        List<VectorRecord> hits = service.recall(NS, "reset database password", 3, 0);

        assertFalse(hits.isEmpty());
        assertEquals("k1", hits.get(0).getId(), "最相关记录应排在首位");
        assertTrue(hits.get(0).getScore() >= hits.get(1).getScore(), "结果应按相似度降序");
    }

    @Test
    @DisplayName("namespace 隔离：不同命名空间互不召回")
    void namespaceIsolation() {
        service.remember("tenant-a", "k1", "shared infra config alpha", Map.of());
        service.remember("tenant-b", "k2", "shared infra config alpha", Map.of());

        List<VectorRecord> hits = service.recall("tenant-a", "shared infra config alpha", 10, 0);

        assertEquals(1, hits.size());
        assertEquals("k1", hits.get(0).getId());
        assertEquals("tenant-a", hits.get(0).getNamespace());
    }

    @Test
    @DisplayName("Token 预算约束：超预算的记录被截断")
    void tokenBudgetTruncation() {
        service.remember(NS, "k1", "aaaa bbbb cccc", Map.of());
        service.remember(NS, "k2", "aaaa bbbb cccc dddd", Map.of());

        List<VectorRecord> all = service.recall(NS, "aaaa bbbb cccc", 10, 0);
        assertEquals(2, all.size());

        // 单条估算 token = len/4 + 1；预算只够 1 条
        long oneToken = all.get(0).getContent().length() / 4L + 1L;
        List<VectorRecord> budgeted = service.recall(NS, "aaaa bbbb cccc", 10, oneToken);
        assertEquals(1, budgeted.size(), "预算仅够首条");
    }

    @Test
    @DisplayName("UPSERT 语义：同 id 覆盖而非重复")
    void upsertOverwrites() {
        service.remember(NS, "k1", "original content about deploy", Map.of());
        service.remember(NS, "k1", "updated content about deploy pipeline", Map.of("v", 2));

        List<VectorRecord> hits = service.recall(NS, "deploy pipeline", 10, 0);

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getContent().contains("updated"));
    }

    @Test
    @DisplayName("recallAsMemories 产出 L4 知识记忆条目")
    void recallAsMemories() {
        service.remember(NS, "k1", "knowledge about kubernetes scheduling", Map.of());

        List<MemoryEntry> memories = service.recallAsMemories(NS, "kubernetes scheduling", 5, 0);

        assertEquals(1, memories.size());
        assertEquals(MemoryLevel.L4_KNOWLEDGE, memories.get(0).getLevel());
        assertTrue(memories.get(0).getTokenEstimate() > 0);
    }

    @Test
    @DisplayName("边界：空查询/空写入不产生结果")
    void blankGuards() {
        service.remember(NS, "k1", "", Map.of());
        assertTrue(service.recall(NS, "anything", 5, 0).isEmpty(), "无有效写入应无召回");

        service.remember(NS, "k2", "valid content here", Map.of());
        assertTrue(service.recall(NS, "   ", 5, 0).isEmpty(), "空白查询应无召回");
        assertTrue(service.recall(NS, "valid content", 0, 0).isEmpty(), "topK<=0 应无召回");
    }
}
