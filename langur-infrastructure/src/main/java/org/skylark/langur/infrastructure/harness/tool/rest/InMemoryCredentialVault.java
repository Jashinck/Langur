package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CredentialVault} 内存实现 - 启动时由 {@code RestApiToolRegistrar} 从配置装载。
 * <p>密钥仅驻留内存、按需转换为请求头，禁止 toString/日志暴露。</p>
 */
@Component
public class InMemoryCredentialVault implements CredentialVault {

    private final Map<String, Credential> store = new ConcurrentHashMap<>();

    public void put(Credential credential) {
        if (credential != null && credential.getRef() != null) {
            store.put(credential.getRef(), credential);
        }
    }

    public void putAll(List<Credential> credentials) {
        if (credentials != null) {
            credentials.forEach(this::put);
        }
    }

    @Override
    public Optional<Credential> find(String ref) {
        return ref == null ? Optional.empty() : Optional.ofNullable(store.get(ref));
    }

    @Override
    public Map<String, String> resolveHeaders(String ref) {
        Map<String, String> headers = new HashMap<>();
        Optional<Credential> credential = find(ref);
        if (credential.isEmpty()) {
            return headers;
        }
        Credential c = credential.get();
        switch (c.getType()) {
            case BEARER -> headers.put("Authorization", "Bearer " + c.getSecret());
            case API_KEY -> {
                String name = c.getHeaderName() != null ? c.getHeaderName() : "X-Api-Key";
                headers.put(name, c.getSecret());
            }
            case BASIC -> {
                String raw = (c.getUsername() != null ? c.getUsername() : "") + ":" + c.getSecret();
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
        }
        return headers;
    }
}
