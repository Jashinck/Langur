package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>密钥仅驻留内存、按需转换为请求头，禁止 toString/日志暴露。H7：密钥字段支持 {@code env:}/{@code prop:}/
 * {@code kms:} 引用，经 {@link SecretResolver} 按需解密（明文不落配置/日志）；OAUTH2 凭证经
 * {@link OAuth2TokenManager} 换取并缓存/刷新访问令牌。为兼容纯单测保留无参构造（字面量密钥直通、无令牌管理器）。</p>
 */
@Component
public class InMemoryCredentialVault implements CredentialVault {

    private final Map<String, Credential> store = new ConcurrentHashMap<>();
    private final SecretResolver secretResolver;
    private final OAuth2TokenManager tokenManager;

    public InMemoryCredentialVault() {
        this((SecretResolver) null, null);
    }

    @Autowired
    public InMemoryCredentialVault(ObjectProvider<SecretResolver> secretResolverProvider,
                                   ObjectProvider<OAuth2TokenManager> tokenManagerProvider) {
        this(secretResolverProvider.getIfAvailable(), tokenManagerProvider.getIfAvailable());
    }

    public InMemoryCredentialVault(SecretResolver secretResolver, OAuth2TokenManager tokenManager) {
        this.secretResolver = secretResolver;
        this.tokenManager = tokenManager;
    }

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
            case BEARER -> headers.put("Authorization", "Bearer " + resolveSecret(c.getSecret()));
            case API_KEY -> {
                String name = c.getHeaderName() != null ? c.getHeaderName() : "X-Api-Key";
                headers.put(name, resolveSecret(c.getSecret()));
            }
            case BASIC -> {
                String raw = (c.getUsername() != null ? c.getUsername() : "") + ":" + resolveSecret(c.getSecret());
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
            case OAUTH2 -> {
                if (tokenManager == null) {
                    throw new IllegalStateException(
                            "OAUTH2 credential [" + c.getRef() + "] requires an OAuth2TokenManager, none configured");
                }
                String clientSecret = resolveSecret(c.getClientSecret());
                String token = tokenManager.accessToken(c.tokenCacheKey(), c.getTokenUrl(),
                        c.getClientId(), clientSecret, c.getScope());
                headers.put("Authorization", "Bearer " + token);
            }
        }
        return headers;
    }

    /** 解析密钥引用为明文；无解析器时字面量直通（单测），引用不可解析则 fail-closed。 */
    private String resolveSecret(String raw) {
        if (raw == null) {
            return null;
        }
        if (secretResolver == null) {
            return raw;
        }
        return secretResolver.resolve(raw)
                .orElseThrow(() -> new IllegalStateException(
                        "cannot resolve secret reference (missing env/prop/kms value)"));
    }
}
