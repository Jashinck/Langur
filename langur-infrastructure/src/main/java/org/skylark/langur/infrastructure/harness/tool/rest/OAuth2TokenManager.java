package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * OAuth2 令牌管理器（H7，§7.4）- 缓存 client_credentials 访问令牌并在临近到期时自动刷新。
 * <p>按凭证 {@code tokenCacheKey} 隔离缓存；提前 {@code refreshSkewMillis} 刷新以规避边界失效；
 * 服务端未声明有效期（expires_in<=0）时不缓存，每次重新获取。时钟可注入以便离线单测刷新逻辑。</p>
 */
@Component
public class OAuth2TokenManager {

    private static final long DEFAULT_REFRESH_SKEW_MILLIS = 30_000L;

    private final OAuth2TokenClient client;
    private final LongSupplier clock;
    private final long refreshSkewMillis;
    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public OAuth2TokenManager(ObjectProvider<OAuth2TokenClient> clientProvider) {
        this(clientProvider.getIfAvailable(), System::currentTimeMillis, DEFAULT_REFRESH_SKEW_MILLIS);
    }

    public OAuth2TokenManager(OAuth2TokenClient client, LongSupplier clock) {
        this(client, clock, DEFAULT_REFRESH_SKEW_MILLIS);
    }

    public OAuth2TokenManager(OAuth2TokenClient client, LongSupplier clock, long refreshSkewMillis) {
        this.client = client;
        this.clock = clock;
        this.refreshSkewMillis = refreshSkewMillis >= 0 ? refreshSkewMillis : DEFAULT_REFRESH_SKEW_MILLIS;
    }

    /** 返回有效访问令牌：命中未过期缓存直接复用，否则获取并缓存（双重检查 + 按键加锁）。 */
    public String accessToken(String cacheKey, String tokenUrl, String clientId, String clientSecret, String scope) {
        if (client == null) {
            throw new IllegalStateException("OAUTH2 credential requires an OAuth2TokenClient, none configured");
        }
        String key = cacheKey != null ? cacheKey : (clientId + "@" + tokenUrl);
        CachedToken cached = validCached(key);
        if (cached != null) {
            return cached.token();
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = validCached(key);
            if (cached != null) {
                return cached.token();
            }
            AccessToken fetched = client.fetchToken(tokenUrl, clientId, clientSecret, scope);
            if (fetched == null || fetched.token() == null || fetched.token().isBlank()) {
                throw new IllegalStateException("OAuth2 token endpoint returned no access_token");
            }
            if (fetched.expiresInSeconds() > 0) {
                cache.put(key, new CachedToken(fetched.token(),
                        clock.getAsLong() + fetched.expiresInSeconds() * 1000L));
            } else {
                cache.remove(key);
            }
            return fetched.token();
        }
    }

    private CachedToken validCached(String key) {
        CachedToken cached = cache.get(key);
        if (cached != null && clock.getAsLong() < cached.expiresAtMillis() - refreshSkewMillis) {
            return cached;
        }
        return null;
    }

    private record CachedToken(String token, long expiresAtMillis) {
    }
}
