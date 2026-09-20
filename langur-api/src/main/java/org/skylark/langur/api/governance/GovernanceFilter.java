package org.skylark.langur.api.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.skylark.langur.api.dto.Result;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * API 治理链过滤器（§13.3）：TraceId → 鉴权 → 租户 → 限流 → 幂等 → 业务处理。
 * <p>解析出的可信 user/tenant 写入 {@link RequestContext} 与 MDC（全链路同一 traceId）；
 * 幂等键命中时回放首次响应，处理中返回 409。默认 {@code langur.governance.enabled=false} 时透传。</p>
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class GovernanceFilter extends OncePerRequestFilter {

    private static final String MDC_TRACE_ID = "traceId";

    private final GovernanceProperties properties;
    private final InMemoryRateLimiter rateLimiter;
    private final InMemoryIdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(properties.getTraceHeader(), traceId);
        RequestContext.set(traceId, request.getHeader(properties.getUserHeader()),
                request.getHeader(properties.getTenantHeader()));

        try {
            if (!properties.isEnabled() || !isProtected(request)) {
                chain.doFilter(request, response);
                return;
            }

            // [1] 鉴权
            GovernanceProperties.AuthToken token = null;
            if (properties.getAuth().isEnabled()) {
                token = authenticate(request);
                if (token == null) {
                    writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "missing or invalid credential");
                    return;
                }
            }

            // [2] 租户校验（可信身份覆盖业务入参 + 越租户阻断）
            String requestTenant = request.getHeader(properties.getTenantHeader());
            String effectiveTenant = token != null ? token.getTenant() : requestTenant;
            String effectiveUser = token != null ? token.getUser() : request.getHeader(properties.getUserHeader());
            if (token != null && requestTenant != null && !requestTenant.equals(token.getTenant())) {
                writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "cross-tenant access denied");
                return;
            }
            RequestContext.set(traceId, effectiveUser, effectiveTenant);

            // [3] 限流
            String limitKey = effectiveTenant != null ? effectiveTenant
                    : (effectiveUser != null ? effectiveUser : traceId);
            if (!rateLimiter.tryAcquire(limitKey, properties.getRateLimit().getPermitsPerMinute())) {
                writeError(response, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "rate limit exceeded");
                return;
            }

            // [4] 幂等拦截
            String idempotencyKey = request.getHeader(properties.getIdempotencyHeader());
            if (properties.isIdempotencyEnabled() && idempotencyKey != null && !idempotencyKey.isBlank()) {
                if (!handleIdempotent(request, response, chain, idempotencyKey)) {
                    return;
                }
                return;
            }

            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            RequestContext.clear();
        }
    }

    /**
     * 处理幂等请求。返回 true 表示已放行并完成（无需再走链路）；false 表示已回放/拒绝（无需再走链路）。
     */
    private boolean handleIdempotent(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain, String key) throws IOException, ServletException {
        var existing = idempotencyStore.find(key);
        if (existing.isPresent()) {
            var entry = existing.get();
            if (entry.completed()) {
                replay(response, entry.response());
            } else {
                writeError(response, HttpStatus.CONFLICT, "CONFLICT", "idempotent request in progress");
            }
            return false;
        }
        if (!idempotencyStore.reserve(key)) {
            // 并发抢占有两种结果，统一按处理中拒绝
            writeError(response, HttpStatus.CONFLICT, "CONFLICT", "idempotent request in progress");
            return false;
        }
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            byte[] body = wrapper.getContentAsByteArray();
            idempotencyStore.complete(key,
                    new InMemoryIdempotencyStore.StoredResponse(wrapper.getStatus(), wrapper.getContentType(), body));
            wrapper.copyBodyToResponse();
        }
        return true;
    }

    private void replay(HttpServletResponse response, InMemoryIdempotencyStore.StoredResponse stored)
            throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        if (stored.body() != null && stored.body().length > 0) {
            response.getOutputStream().write(stored.body());
        }
    }

    private GovernanceProperties.AuthToken authenticate(HttpServletRequest request) {
        String credential = extractCredential(request);
        if (credential == null) {
            return null;
        }
        return properties.getAuth().getTokens().stream()
                .filter(t -> credential.equals(t.getToken()))
                .findFirst()
                .orElse(null);
    }

    private String extractCredential(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return request.getHeader("X-Api-Key");
    }

    private boolean isProtected(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(properties.getProtectedPrefix());
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(properties.getTraceHeader());
        return (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }
}
