package org.skylark.langur.infrastructure.harness.tool.rest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7 验收：{@link OAuth2TokenManager} client_credentials 令牌自动获取 + 缓存 + 到期刷新。
 * 以桩令牌客户端（计数）+ 可推进假时钟离线验证：命中未过期缓存不重复获取，临近/超过有效期触发刷新，
 * 服务端未声明有效期（expires_in<=0）不缓存，缺令牌客户端 fail-fast。
 */
class OAuth2TokenManagerTest {

    /** 桩令牌客户端：每次调用发一个递增令牌并计数。 */
    private static final class StubTokenClient implements OAuth2TokenClient {
        final AtomicInteger calls = new AtomicInteger();
        long expiresInSeconds = 3600;

        @Override
        public AccessToken fetchToken(String tokenUrl, String clientId, String clientSecret, String scope) {
            int n = calls.incrementAndGet();
            return new AccessToken("tok-" + n, expiresInSeconds);
        }
    }

    @Test
    void shouldCacheTokenUntilNearExpiryThenRefresh() {
        StubTokenClient client = new StubTokenClient();
        AtomicLong now = new AtomicLong(1_000_000L);
        OAuth2TokenManager manager = new OAuth2TokenManager(client, now::get, 30_000L);

        String first = manager.accessToken("ref", "https://idp/token", "cid", "csecret", "scope");
        String second = manager.accessToken("ref", "https://idp/token", "cid", "csecret", "scope");
        assertEquals("tok-1", first);
        assertEquals("tok-1", second, "within validity should reuse cached token");
        assertEquals(1, client.calls.get());

        // 推进到临近到期（剩余 < skew）→ 触发刷新
        now.addAndGet(3600_000L - 29_000L);
        String third = manager.accessToken("ref", "https://idp/token", "cid", "csecret", "scope");
        assertEquals("tok-2", third);
        assertEquals(2, client.calls.get());
    }

    @Test
    void shouldNotCacheWhenExpiresInNonPositive() {
        StubTokenClient client = new StubTokenClient();
        client.expiresInSeconds = 0;
        AtomicLong now = new AtomicLong(0L);
        OAuth2TokenManager manager = new OAuth2TokenManager(client, now::get);

        assertEquals("tok-1", manager.accessToken("ref", "u", "cid", "sec", null));
        assertEquals("tok-2", manager.accessToken("ref", "u", "cid", "sec", null));
        assertEquals(2, client.calls.get(), "no expiry declared → always refetch");
    }

    @Test
    void shouldIsolateCachePerKey() {
        StubTokenClient client = new StubTokenClient();
        AtomicLong now = new AtomicLong(0L);
        OAuth2TokenManager manager = new OAuth2TokenManager(client, now::get);

        assertEquals("tok-1", manager.accessToken("a", "u", "cid", "sec", null));
        assertEquals("tok-2", manager.accessToken("b", "u", "cid", "sec", null));
        assertEquals("tok-1", manager.accessToken("a", "u", "cid", "sec", null));
        assertEquals(2, client.calls.get());
    }

    @Test
    void shouldThrowWhenNoTokenClient() {
        OAuth2TokenManager manager = new OAuth2TokenManager(null, System::currentTimeMillis);
        assertThrows(IllegalStateException.class,
                () -> manager.accessToken("ref", "u", "cid", "sec", null));
    }

    @Test
    void shouldThrowWhenEndpointReturnsNoToken() {
        OAuth2TokenClient empty = (tokenUrl, clientId, clientSecret, scope) -> new AccessToken("  ", 3600);
        OAuth2TokenManager manager = new OAuth2TokenManager(empty, System::currentTimeMillis);
        assertThrows(IllegalStateException.class,
                () -> manager.accessToken("ref", "u", "cid", "sec", null));
    }
}
