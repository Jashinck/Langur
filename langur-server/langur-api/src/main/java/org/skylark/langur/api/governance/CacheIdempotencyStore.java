package org.skylark.langur.api.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.common.cache.CacheBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 幂等存储（§13.3 / H4）- 缓存后端持久化实现（{@code langur.cache.type=redis} 时装配）。
 * <p>幂等键与首次响应以 JSON 落缓存后端（带 TTL），多实例共享：跨实例重复请求回放首次结果。
 * 两阶段占位（IN_PROGRESS 标记）→ 完成（序列化响应）；后端不可用时由其内部降级内存（P10）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.cache.type", havingValue = "redis")
public class CacheIdempotencyStore implements IdempotencyStore {

    private static final String IN_PROGRESS = "__IN_PROGRESS__";

    private final CacheBackend cacheBackend;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public CacheIdempotencyStore(CacheBackend cacheBackend,
                                 ObjectMapper objectMapper,
                                 @Value("${langur.cache.ttl-seconds:1800}") long ttlSeconds) {
        this.cacheBackend = cacheBackend;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    private String k(String key) {
        return "idem:" + key;
    }

    @Override
    public Optional<Entry> find(String key) {
        Optional<String> raw = cacheBackend.get(k(key));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String value = raw.get();
        if (IN_PROGRESS.equals(value)) {
            return Optional.of(Entry.inProgress());
        }
        try {
            Payload payload = objectMapper.readValue(value, Payload.class);
            byte[] body = payload.bodyBase64() != null ? Base64.getDecoder().decode(payload.bodyBase64()) : null;
            return Optional.of(Entry.completed(new StoredResponse(payload.status(), payload.contentType(), body)));
        } catch (Exception e) {
            log.warn("[H4] idempotency payload decode failed for key [{}], treat as absent", key, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean reserve(String key) {
        return cacheBackend.setIfAbsent(k(key), IN_PROGRESS, ttl);
    }

    @Override
    public void complete(String key, StoredResponse response) {
        try {
            String bodyBase64 = response.body() != null ? Base64.getEncoder().encodeToString(response.body()) : null;
            Payload payload = new Payload(response.status(), response.contentType(), bodyBase64);
            cacheBackend.put(k(key), objectMapper.writeValueAsString(payload), ttl);
        } catch (Exception e) {
            log.warn("[H4] idempotency payload encode failed for key [{}]", key, e);
        }
    }

    /** 可序列化载荷（body 以 Base64 承载字节）。 */
    record Payload(int status, String contentType, String bodyBase64) {
    }
}
