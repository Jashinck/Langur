package org.skylark.langur.infrastructure.harness.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.common.cache.CacheBackend;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.cache.MemoryCacheBackend;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J3 验收 - {@link CachingDecisionPort} 离线确定性单测（纯 JUnit5 + H4 {@link MemoryCacheBackend}）。
 * <p>覆盖：命中缓存不重复调后端、命中返回空计量（无新增成本）、不同 state 分别回源、
 * 关闭/无问题透传、缓存异常静默降级委派后端（P10）、缓存键含 state+问题签名。</p>
 */
class CachingDecisionPortTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 计数后端桩：记录回源次数并返回带 usage 的固定判定。 */
    private static final class CountingBackend implements DecisionPort {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls.incrementAndGet();
            return new DecisionResponse(Map.of(
                    "route", DecisionAnswer.ofChoice("react", 0.9d, Map.of("react", 0.9d, "plan", 0.1d))),
                    LLMPort.TokenUsage.of(50L, 0L, 50L));
        }
    }

    private static DecisionRequest request(String state) {
        return DecisionRequest.of(state, "jev-1.13.0", List.of(
                DecisionQuestion.choice("route", "选择范式", Map.of("react", "简单", "plan", "复杂"))));
    }

    private CachingDecisionPort newPort(DecisionPort delegate, CacheBackend cache, boolean enabled) {
        return new CachingDecisionPort(delegate, cache, MAPPER, enabled, Duration.ofSeconds(300));
    }

    @Test
    void shouldServeSecondIdenticalRequestFromCacheWithoutBackendCall() {
        CountingBackend backend = new CountingBackend();
        CachingDecisionPort port = newPort(backend, new MemoryCacheBackend(), true);

        DecisionResponse first = port.decide(request("合同正文"));
        DecisionResponse second = port.decide(request("合同正文"));

        assertEquals(1, backend.calls.get(), "相同 state+问题应命中缓存，后端只调一次");
        assertEquals(50L, first.usage().getTotalTokens(), "回源响应带真实 usage");
        assertTrue(second.usage().isEmpty(), "缓存命中 → 空计量（无新增成本）");
        assertEquals("react", second.answer("route").choice(), "缓存命中判定一致");
        assertEquals(0.9d, second.answer("route").confidence(), 1e-9);
    }

    @Test
    void shouldCallBackendForEachDistinctState() {
        CountingBackend backend = new CountingBackend();
        CachingDecisionPort port = newPort(backend, new MemoryCacheBackend(), true);

        port.decide(request("state-A"));
        port.decide(request("state-B"));

        assertEquals(2, backend.calls.get(), "不同 state 缓存键不同，分别回源");
    }

    @Test
    void shouldBypassCacheWhenDisabled() {
        CountingBackend backend = new CountingBackend();
        CachingDecisionPort port = newPort(backend, new MemoryCacheBackend(), false);

        port.decide(request("同一 state"));
        port.decide(request("同一 state"));

        assertEquals(2, backend.calls.get(), "cache.enabled=false 时每次透传后端");
    }

    @Test
    void shouldBypassCacheWhenNoQuestions() {
        CountingBackend backend = new CountingBackend();
        CachingDecisionPort port = newPort(backend, new MemoryCacheBackend(), true);

        port.decide(DecisionRequest.of("state"));

        assertEquals(1, backend.calls.get(), "无问题直接委派，不缓存");
    }

    @Test
    void shouldDegradeGracefullyWhenCacheThrows() {
        CountingBackend backend = new CountingBackend();
        CacheBackend broken = new CacheBackend() {
            @Override
            public long increment(String key, Duration ttl) {
                throw new IllegalStateException("cache down");
            }

            @Override
            public boolean setIfAbsent(String key, String value, Duration ttl) {
                throw new IllegalStateException("cache down");
            }

            @Override
            public Optional<String> get(String key) {
                throw new IllegalStateException("cache down");
            }

            @Override
            public void put(String key, String value, Duration ttl) {
                throw new IllegalStateException("cache down");
            }

            @Override
            public void evict(String key) {
                throw new IllegalStateException("cache down");
            }
        };
        CachingDecisionPort port = newPort(backend, broken, true);

        DecisionResponse response = port.decide(request("state"));

        assertNotNull(response, "缓存故障不得抛出，应委派后端（P10）");
        assertEquals("react", response.answer("route").choice());
        assertEquals(1, backend.calls.get());
    }

    @Test
    void shouldExposeDelegateAndStableCacheKey() {
        CountingBackend backend = new CountingBackend();
        CachingDecisionPort port = newPort(backend, new MemoryCacheBackend(), true);

        assertSame(backend, port.getDelegate());
        assertEquals(port.cacheKey(request("s")), port.cacheKey(request("s")), "相同请求缓存键稳定");
        assertTrue(!port.cacheKey(request("s")).equals(port.cacheKey(request("t"))), "不同 state 键不同");
    }
}
