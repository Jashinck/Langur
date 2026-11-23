package org.skylark.langur.api.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 验收：治理链 鉴权(401) → 租户(403) → 限流(429) → 幂等(回放) → TraceId 透传。
 */
class GovernanceFilterTest {

    private GovernanceProperties properties;
    private InMemoryRateLimiter rateLimiter;
    private InMemoryIdempotencyStore idempotencyStore;
    private GovernanceFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GovernanceProperties();
        properties.setEnabled(true);
        GovernanceProperties.AuthToken token = new GovernanceProperties.AuthToken();
        token.setToken("secret-token-1");
        token.setTenant("tenant-a");
        token.setUser("user-1");
        properties.getAuth().setTokens(List.of(token));
        rateLimiter = new InMemoryRateLimiter();
        idempotencyStore = new InMemoryIdempotencyStore();
        filter = new GovernanceFilter(properties, rateLimiter, idempotencyStore, objectMapper);
    }

    private MockHttpServletRequest apiRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/chat");
        request.setRequestURI("/api/v1/agent/chat");
        return request;
    }

    private FilterChain countingChain(AtomicInteger counter) {
        return (req, res) -> {
            counter.incrementAndGet();
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(200);
            response.setContentType("application/json");
            response.getWriter().write("DOWNSTREAM-BODY");
        };
    }

    @Test
    void shouldReturn401WhenNoCredential() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(apiRequest(), response, countingChain(invocations));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldPassThroughWithValidTokenAndSetTraceId() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", "Bearer secret-token-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, countingChain(invocations));

        assertEquals(200, response.getStatus());
        assertEquals(1, invocations.get());
        assertNotNull(response.getHeader(properties.getTraceHeader()));
        assertEquals("DOWNSTREAM-BODY", response.getContentAsString());
    }

    @Test
    void shouldReturn403OnCrossTenant() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", "Bearer secret-token-1");
        request.addHeader(properties.getTenantHeader(), "tenant-b"); // 与令牌绑定的 tenant-a 不符
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, countingChain(invocations));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        properties.getRateLimit().setPermitsPerMinute(1);
        AtomicInteger invocations = new AtomicInteger();

        MockHttpServletRequest first = apiRequest();
        first.addHeader("Authorization", "Bearer secret-token-1");
        filter.doFilter(first, new MockHttpServletResponse(), countingChain(invocations));

        MockHttpServletRequest second = apiRequest();
        second.addHeader("Authorization", "Bearer secret-token-1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, countingChain(invocations));

        assertEquals(429, secondResponse.getStatus());
        assertEquals(1, invocations.get()); // 仅首个请求放行
    }

    @Test
    void shouldReplayFirstResponseForDuplicateIdempotencyKey() throws Exception {
        AtomicInteger invocations = new AtomicInteger();

        MockHttpServletRequest first = apiRequest();
        first.addHeader("Authorization", "Bearer secret-token-1");
        first.addHeader(properties.getIdempotencyHeader(), "idem-1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, countingChain(invocations));

        MockHttpServletRequest second = apiRequest();
        second.addHeader("Authorization", "Bearer secret-token-1");
        second.addHeader(properties.getIdempotencyHeader(), "idem-1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, countingChain(invocations));

        assertEquals(1, invocations.get()); // 下游只执行一次
        assertEquals("DOWNSTREAM-BODY", firstResponse.getContentAsString());
        assertEquals("DOWNSTREAM-BODY", secondResponse.getContentAsString()); // 第二次回放首次结果
    }

    @Test
    void shouldPassThroughWhenGovernanceDisabled() throws Exception {
        properties.setEnabled(false);
        AtomicInteger invocations = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(apiRequest(), response, countingChain(invocations));

        assertEquals(200, response.getStatus());
        assertEquals(1, invocations.get());
        assertNotNull(response.getHeader(properties.getTraceHeader())); // TraceId 仍然生成
    }
}
