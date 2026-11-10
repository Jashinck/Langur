package org.skylark.langur.domain.harness.context.vector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.3 验收（domain 侧）- {@link VectorMemoryService#recall} 混合检索分支：
 * {@code enabled && mode=native && supportsHybrid()} 走原生下推；原生异常回退应用侧；
 * {@code mode=app} 或未启用时走既有 search + rerank（memory/pgvector 路径完全不变，P10）。
 * <p>纯 JUnit5 + 内部类 stub（domain 禁用 Mockito）。</p>
 */
class VectorMemoryServiceHybridTest {

    static class FixedEmbedding implements EmbeddingPort {
        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f};
        }

        @Override
        public int dimensions() {
            return 2;
        }
    }

    /** 可编程 store：记录 hybrid/search 调用轨迹，可令 hybridSearch 抛异常验证兜底。 */
    static class ProgrammableStore implements VectorStore {
        boolean hybridSupported = true;
        boolean throwOnHybrid = false;
        boolean hybridCalled = false;
        boolean searchCalled = false;
        List<VectorRecord> hybridResult = List.of(record("native-1"));
        List<VectorRecord> searchResult = List.of(record("vec-1"));

        @Override
        public void upsert(VectorRecord record) {
        }

        @Override
        public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
            searchCalled = true;
            return searchResult;
        }

        @Override
        public boolean supportsHybrid() {
            return hybridSupported;
        }

        @Override
        public List<VectorRecord> hybridSearch(HybridQuery query) {
            hybridCalled = true;
            if (throwOnHybrid) {
                throw new IllegalStateException("native hybrid down");
            }
            return hybridResult;
        }
    }

    static class CountingRerank implements RerankPort {
        int calls = 0;

        @Override
        public List<VectorRecord> rerank(String query, List<VectorRecord> candidates, int topK) {
            calls++;
            return candidates;
        }
    }

    private static VectorRecord record(String id) {
        return VectorRecord.builder().id(id).namespace("ns").content("content-" + id).build();
    }

    private static HybridSearchOptions nativeEnabled() {
        return HybridSearchOptions.of(true, HybridSearchOptions.Mode.NATIVE, FusionMode.RRF, 60, 0.3);
    }

    @Test
    void shouldUseNativeHybridWhenEnabledAndSupported() {
        ProgrammableStore store = new ProgrammableStore();
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, null, nativeEnabled());

        List<VectorRecord> hits = service.recall("ns", "查询", 5, 0);

        assertTrue(store.hybridCalled, "应走原生混合下推");
        assertFalse(store.searchCalled, "原生命中时不再走纯向量 search");
        assertEquals("native-1", hits.get(0).getId());
    }

    @Test
    void shouldFallbackToAppSideOnNativeException() {
        ProgrammableStore store = new ProgrammableStore();
        store.throwOnHybrid = true;
        CountingRerank rerank = new CountingRerank();
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, rerank, nativeEnabled());

        List<VectorRecord> hits = service.recall("ns", "查询", 5, 0);

        assertTrue(store.hybridCalled, "先尝试原生");
        assertTrue(store.searchCalled, "原生异常后回退纯向量 search");
        assertEquals(1, rerank.calls, "回退路径仍经应用侧 rerank");
        assertEquals("vec-1", hits.get(0).getId(), "返回兜底结果，主链路不中断（P10）");
    }

    @Test
    void shouldForceAppSideWhenModeIsApp() {
        ProgrammableStore store = new ProgrammableStore();
        HybridSearchOptions appMode = HybridSearchOptions.of(
                true, HybridSearchOptions.Mode.APP, FusionMode.RRF, 60, 0.3);
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, null, appMode);

        service.recall("ns", "查询", 5, 0);

        assertFalse(store.hybridCalled, "mode=app 强制应用侧，不走原生");
        assertTrue(store.searchCalled);
    }

    @Test
    void shouldUseAppSideWhenHybridDisabledByDefault() {
        ProgrammableStore store = new ProgrammableStore();
        // 缺省构造 = HybridSearchOptions.disabled()
        VectorMemoryService service = new VectorMemoryService(new FixedEmbedding(), store, null);

        service.recall("ns", "查询", 5, 0);

        assertFalse(store.hybridCalled, "缺省混合关闭：memory/pgvector 路径完全不变");
        assertTrue(store.searchCalled);
    }

    @Test
    void shouldNotUseNativeWhenStoreLacksCapability() {
        ProgrammableStore store = new ProgrammableStore();
        store.hybridSupported = false;
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, null, nativeEnabled());

        service.recall("ns", "查询", 5, 0);

        assertFalse(store.hybridCalled, "supportsHybrid=false 时即便配置启用也走应用侧");
        assertTrue(store.searchCalled);
    }

    @Test
    void shouldRespectTokenBudgetOnNativePath() {
        ProgrammableStore store = new ProgrammableStore();
        store.hybridResult = List.of(record("a"), record("b"));
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, null, nativeEnabled());

        long oneToken = "content-a".length() / 4L + 1L;
        List<VectorRecord> budgeted = service.recall("ns", "查询", 5, oneToken);

        assertEquals(1, budgeted.size(), "原生路径同样受 Token 预算截断");
    }

    @Test
    void shouldReturnEmptyOnBlankQueryForNativePath() {
        ProgrammableStore store = new ProgrammableStore();
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, null, nativeEnabled());

        assertTrue(service.recall("ns", "   ", 5, 0).isEmpty());
        assertFalse(store.hybridCalled, "空查询提前返回，不触发检索");
    }

    @Test
    void shouldCarryFusionParamsIntoHybridQuery() {
        CapturingStore store = new CapturingStore();
        HybridSearchOptions weighted = HybridSearchOptions.of(
                true, HybridSearchOptions.Mode.NATIVE, FusionMode.WEIGHTED, 30, 0.5);
        VectorMemoryService service = new VectorMemoryService(
                new FixedEmbedding(), store, null, weighted);

        service.recall("ns", "查询", 7, 0);

        assertEquals(FusionMode.WEIGHTED, store.captured.getFusion());
        assertEquals(30, store.captured.getRrfK());
        assertEquals(0.5, store.captured.getLexicalWeight());
        assertEquals(7, store.captured.getTopK());
        assertEquals("查询", store.captured.getQueryText());
    }

    /** 捕获传入的 HybridQuery 以断言融合参数透传。 */
    static class CapturingStore implements VectorStore {
        HybridQuery captured;

        @Override
        public void upsert(VectorRecord record) {
        }

        @Override
        public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public boolean supportsHybrid() {
            return true;
        }

        @Override
        public List<VectorRecord> hybridSearch(HybridQuery query) {
            this.captured = query;
            return List.of(record("n"));
        }
    }
}
