package org.skylark.langur.infrastructure.harness.decision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.common.cache.CacheBackend;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.evaluation.Checksums;
import org.skylark.langur.domain.port.DecisionPort;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 决策平面缓存装饰器（J3）。按 {@code state} 哈希 + 问题签名缓存判定，复用 H4 {@link CacheBackend}
 * （多闸门不重复调用后端）。命中即返回缓存判定且 {@code usage} 为空计量——缓存命中未触后端、无新增 token 成本，
 * 语义正确（外层 {@link RecordingDecisionPort} 据此记 {@code decision_cost=0}）。
 * <p>缓存读写/序列化失败一律静默降级为直接委派后端（P10），绝不因缓存反噬主链路。{@code cache} 缺件或
 * {@code enabled=false} 时透传。本类不带 {@code @Component}，由 J3 {@code DecisionConfiguration} 装配于
 * {@link ThresholdRouter} 之外、{@link RecordingDecisionPort} 之内。</p>
 */
@Slf4j
public class CachingDecisionPort implements DecisionPort {

    private static final String KEY_PREFIX = "langur:decision:";
    private static final TypeReference<Map<String, DecisionAnswer>> ANSWERS_TYPE =
            new TypeReference<>() {
            };

    private final DecisionPort delegate;
    private final CacheBackend cache;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;

    public CachingDecisionPort(DecisionPort delegate,
                               CacheBackend cache,
                               ObjectMapper objectMapper,
                               boolean enabled,
                               Duration ttl) {
        this.delegate = delegate;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = (ttl == null) ? Duration.ofSeconds(300L) : ttl;
    }

    /** 被包裹的下层端口（供装配顺序校验）。 */
    public DecisionPort getDelegate() {
        return delegate;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        if (!enabled || cache == null || request == null || request.hasNoQuestions()) {
            return delegate.decide(request);
        }
        String key = cacheKey(request);
        DecisionResponse cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        DecisionResponse response = delegate.decide(request);
        writeCache(key, response);
        return response;
    }

    private DecisionResponse readCache(String key) {
        try {
            Optional<String> hit = cache.get(key);
            if (hit.isPresent()) {
                Map<String, DecisionAnswer> answers = objectMapper.readValue(hit.get(), ANSWERS_TYPE);
                // 命中：usage 空计量（无后端往返、无新增成本）
                return DecisionResponse.of(answers);
            }
        } catch (Exception e) {
            log.debug("[DECISION] cache read failed, delegate to backend: {}", e.getMessage());
        }
        return null;
    }

    private void writeCache(String key, DecisionResponse response) {
        if (response == null || response.isEmpty()) {
            return;
        }
        try {
            cache.put(key, objectMapper.writeValueAsString(response.answers()), ttl);
        } catch (Exception e) {
            log.debug("[DECISION] cache write failed, ignored: {}", e.getMessage());
        }
    }

    /** 缓存键：state + model + 问题签名（key/type/instructions/criteria 有序拼接）的 SHA-256。 */
    String cacheKey(DecisionRequest request) {
        StringBuilder signature = new StringBuilder();
        for (DecisionQuestion question : request.questions()) {
            signature.append(question.key()).append('|')
                    .append(question.type().wireName()).append('|')
                    .append(question.instructions()).append('|');
            // TreeMap 保证 criteria 有序 → 键稳定
            new TreeMap<>(question.criteria())
                    .forEach((k, v) -> signature.append(k).append('=').append(v).append(','));
            signature.append(';');
        }
        return KEY_PREFIX + Checksums.sha256(
                request.state(), request.model(), signature.toString());
    }
}
