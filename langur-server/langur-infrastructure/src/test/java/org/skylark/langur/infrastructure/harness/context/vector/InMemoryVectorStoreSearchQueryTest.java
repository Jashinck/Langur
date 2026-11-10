package org.skylark.langur.infrastructure.harness.context.vector;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.SearchFilter;
import org.skylark.langur.domain.harness.context.vector.SearchQuery;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.1 验收（infra 侧）- {@link InMemoryVectorStore} 覆写 {@code search(SearchQuery)}
 * （metadata 过滤 + minScore 阈值）与 {@code delete}；{@code supportsHybrid()=false} 保持应用侧兜底路径。
 * <p>离线确定性：纯内存，无外部依赖。</p>
 */
class InMemoryVectorStoreSearchQueryTest {

    private VectorRecord record(String id, float[] vector, Map<String, Object> metadata) {
        return VectorRecord.builder().id(id).namespace("ns").vector(vector).content("c-" + id)
                .metadata(metadata).build();
    }

    @Test
    void shouldFilterByMetadataPredicate() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(record("zh", new float[]{1f, 0f}, Map.of("lang", "zh")));
        store.upsert(record("en", new float[]{1f, 0f}, Map.of("lang", "en")));

        List<VectorRecord> hits = store.search(SearchQuery.of("ns", new float[]{1f, 0f}, 10)
                .withFilter(SearchFilter.of(SearchFilter.Predicate.eq("lang", "zh"))));

        assertEquals(1, hits.size());
        assertEquals("zh", hits.get(0).getId());
    }

    @Test
    void shouldApplyMinScoreThreshold() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(record("near", new float[]{1f, 0f}, Map.of()));
        store.upsert(record("far", new float[]{0f, 1f}, Map.of()));

        // 与 [1,0] 正交的 [0,1] cosine=0；阈值 0.5 只保留 near
        List<VectorRecord> hits = store.search(SearchQuery.of("ns", new float[]{1f, 0f}, 10)
                .withMinScore(0.5));

        assertEquals(1, hits.size());
        assertEquals("near", hits.get(0).getId());
    }

    @Test
    void shouldKeepLegacySearchUnfilteredWhenMinScoreZero() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(record("a", new float[]{1f, 0f}, Map.of()));
        store.upsert(record("b", new float[]{-1f, 0f}, Map.of()));

        // minScore<=0 视为无阈值：负分记录仍返回（既有 search(ns,vec,topK) 行为不变）
        List<VectorRecord> hits = store.search("ns", new float[]{1f, 0f}, 10);

        assertEquals(2, hits.size(), "无阈值时不因 minScore 丢弃负分记录");
    }

    @Test
    void shouldDeleteRecord() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(record("a", new float[]{1f, 0f}, Map.of()));
        store.upsert(record("b", new float[]{1f, 0f}, Map.of()));

        store.delete("ns", "a");
        List<VectorRecord> hits = store.search("ns", new float[]{1f, 0f}, 10);

        assertEquals(1, hits.size());
        assertEquals("b", hits.get(0).getId());
    }

    @Test
    void shouldNotSupportHybrid() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        assertFalse(store.supportsHybrid(), "memory store 走应用侧兜底，不支持原生混合");
    }

    @Test
    void shouldBatchUpsertViaDefaultMethod() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsertAll("ns", List.of(
                record("a", new float[]{1f, 0f}, Map.of()),
                record("b", new float[]{0f, 1f}, Map.of())));

        assertEquals(2, store.search("ns", new float[]{1f, 0f}, 10).size());
    }

    @Test
    void shouldIsolateNamespaceOnDeleteAndSearch() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(VectorRecord.builder().id("x").namespace("ns-a").vector(new float[]{1f}).content("c").build());
        store.upsert(VectorRecord.builder().id("x").namespace("ns-b").vector(new float[]{1f}).content("c").build());

        store.delete("ns-a", "x");

        assertTrue(store.search("ns-a", new float[]{1f}, 10).isEmpty());
        assertEquals(1, store.search("ns-b", new float[]{1f}, 10).size(), "跨 namespace 删除不得误伤");
        assertThrows(UnsupportedOperationException.class,
                () -> store.hybridSearch(org.skylark.langur.domain.harness.context.vector.HybridQuery.of(
                        "ns-a", "q", new float[]{1f}, 5)));
    }
}
