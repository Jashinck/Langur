package org.skylark.langur.domain.harness.context.vector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.2 验收 - domain 值对象：不可变、纯 JDK、可离线单测；{@link SearchFilter} 七算子求值；
 * namespace 隔离不走 filter（一等字段，由 store 强制）。
 * <p>纯 JUnit5 + 内部类 stub（domain 禁用 Mockito）。</p>
 */
class VectorQueryVoTest {

    @Test
    void shouldEvaluateAllFilterOperators() {
        Map<String, Object> metadata = Map.of("lang", "zh", "level", 3, "tags", List.of("a", "b"));

        assertTrue(SearchFilter.of(SearchFilter.Predicate.eq("lang", "zh")).matches(metadata));
        assertFalse(SearchFilter.of(SearchFilter.Predicate.eq("lang", "en")).matches(metadata));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.in("lang", List.of("zh", "en"))).matches(metadata));
        assertFalse(SearchFilter.of(SearchFilter.Predicate.in("lang", List.of("en", "fr"))).matches(metadata));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.gt("level", 2)).matches(metadata));
        assertFalse(SearchFilter.of(SearchFilter.Predicate.gt("level", 3)).matches(metadata));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.gte("level", 3)).matches(metadata));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.lt("level", 4)).matches(metadata));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.lte("level", 3)).matches(metadata));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.exists("lang")).matches(metadata));
        assertFalse(SearchFilter.of(SearchFilter.Predicate.exists("missing")).matches(metadata));
    }

    @Test
    void shouldCombinePredicatesWithAndSemantics() {
        Map<String, Object> metadata = Map.of("lang", "zh", "level", 3);
        SearchFilter filter = SearchFilter.of(SearchFilter.Predicate.eq("lang", "zh"))
                .and(SearchFilter.Predicate.gte("level", 3));

        assertTrue(filter.matches(metadata));
        assertFalse(filter.and(SearchFilter.Predicate.eq("lang", "en")).matches(metadata),
                "AND 语义：任一谓词不满足即不匹配");
    }

    @Test
    void shouldMatchEverythingWithEmptyFilter() {
        assertTrue(SearchFilter.empty().isEmpty());
        assertTrue(SearchFilter.empty().matches(Map.of()), "空过滤器恒真（不影响召回）");
        assertTrue(SearchFilter.empty().matches(null), "null metadata 对空过滤器仍恒真");
        assertFalse(SearchFilter.of(SearchFilter.Predicate.exists("x")).matches(null),
                "null metadata 下 EXISTS 为假");
    }

    @Test
    void shouldCompareNumbersAcrossTypes() {
        // metadata 数值可能是 Integer，谓词值是 int/long/double —— 按 double 归一比较
        assertTrue(SearchFilter.of(SearchFilter.Predicate.gt("level", 2.5)).matches(Map.of("level", 3)));
        assertTrue(SearchFilter.of(SearchFilter.Predicate.eq("level", 3L)).matches(Map.of("level", 3)));
    }

    @Test
    void shouldExposeImmutableDefaultsForSearchQuery() {
        float[] vector = {1f, 2f, 3f};
        SearchQuery query = SearchQuery.of("ns", vector, 5);

        assertEquals("ns", query.getNamespace());
        assertEquals(5, query.getTopK());
        assertTrue(query.getFilter().isEmpty());
        assertEquals(0.0, query.getMinScore());
        // 防御性副本：改入参/取出值不影响内部状态
        vector[0] = 99f;
        assertEquals(1f, query.getQueryVector()[0]);
        query.getQueryVector()[0] = -1f;
        assertEquals(1f, query.getQueryVector()[0]);
    }

    @Test
    void shouldDeriveNewInstancesViaWithers() {
        SearchQuery base = SearchQuery.of("ns", new float[]{1f}, 5);
        SearchQuery filtered = base.withFilter(SearchFilter.of(SearchFilter.Predicate.eq("k", "v")))
                .withMinScore(0.8)
                .withTopK(3);

        assertTrue(base.getFilter().isEmpty(), "不可变：派生不改动原实例");
        assertEquals(0.0, base.getMinScore());
        assertEquals(3, filtered.getTopK());
        assertEquals(0.8, filtered.getMinScore());
        assertFalse(filtered.getFilter().isEmpty());
    }

    @Test
    void shouldExposeImmutableDefaultsForHybridQuery() {
        HybridQuery query = HybridQuery.of("ns", "查询文本", new float[]{1f, 2f}, 10);

        assertEquals("查询文本", query.getQueryText());
        assertEquals(FusionMode.RRF, query.getFusion(), "DD17 默认 RRF");
        assertEquals(HybridQuery.DEFAULT_RRF_K, query.getRrfK());
        assertEquals(60, query.getRrfK());
        assertEquals(HybridQuery.DEFAULT_LEXICAL_WEIGHT, query.getLexicalWeight());
        assertTrue(query.getFilter().isEmpty());
    }

    @Test
    void shouldDeriveHybridFusionParams() {
        HybridQuery base = HybridQuery.of("ns", "q", new float[]{1f}, 10);
        HybridQuery weighted = base.withFusion(FusionMode.WEIGHTED, 30, 0.5);

        assertEquals(FusionMode.RRF, base.getFusion(), "不可变：派生不改动原实例");
        assertEquals(FusionMode.WEIGHTED, weighted.getFusion());
        assertEquals(30, weighted.getRrfK());
        assertEquals(0.5, weighted.getLexicalWeight());
    }

    /** 仅实现既有两方法的 store：验证 H14.1 default 方法向后兼容（P5 零改动即编译）。 */
    static class LegacyStore implements VectorStore {
        final List<VectorRecord> upserted = new java.util.ArrayList<>();

        @Override
        public void upsert(VectorRecord record) {
            upserted.add(record);
        }

        @Override
        public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
            return List.of(VectorRecord.builder().id("r1").namespace(namespace).content("hit").build());
        }
    }

    @Test
    void shouldProvideBackwardCompatibleDefaults() {
        LegacyStore store = new LegacyStore();

        assertFalse(store.supportsHybrid(), "默认不支持原生混合");
        assertThrows(UnsupportedOperationException.class, () -> store.hybridSearch(
                HybridQuery.of("ns", "q", new float[]{1f}, 5)));
        assertThrows(UnsupportedOperationException.class, () -> store.delete("ns", "id"));

        // search(SearchQuery) 默认降级为既有 search(ns, vec, topK)
        List<VectorRecord> results = store.search(SearchQuery.of("ns", new float[]{1f}, 5));
        assertEquals(1, results.size());
        assertEquals("hit", results.get(0).getContent());

        // upsertAll 默认逐条转调 upsert
        store.upsertAll("ns", List.of(
                VectorRecord.builder().id("a").namespace("ns").build(),
                VectorRecord.builder().id("b").namespace("ns").build()));
        assertEquals(2, store.upserted.size());
    }
}
