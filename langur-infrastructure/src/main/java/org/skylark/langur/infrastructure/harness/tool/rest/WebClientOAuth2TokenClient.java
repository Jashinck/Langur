package org.skylark.langur.infrastructure.harness.tool.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link OAuth2TokenClient} 默认实现（H7）- 以 client_credentials 授权向令牌端点 POST 表单换取访问令牌。
 * <p>令牌端点经 {@link SsrfGuard} 校验（仅公网/白名单）；请求表单含 client_secret，响应令牌均不写日志。</p>
 */
@Slf4j
@Component
public class WebClientOAuth2TokenClient implements OAuth2TokenClient {

    private final SsrfGuard ssrfGuard;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public WebClientOAuth2TokenClient(SsrfGuard ssrfGuard, ObjectMapper objectMapper) {
        this.ssrfGuard = ssrfGuard;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public AccessToken fetchToken(String tokenUrl, String clientId, String clientSecret, String scope) {
        URI uri = URI.create(tokenUrl);
        ssrfGuard.validate(uri, List.of());
        String form = form(clientId, clientSecret, scope);
        String body = webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return parse(body);
    }

    private String form(String clientId, String clientSecret, String scope) {
        StringBuilder sb = new StringBuilder("grant_type=client_credentials");
        if (clientId != null) {
            sb.append("&client_id=").append(enc(clientId));
        }
        if (clientSecret != null) {
            sb.append("&client_secret=").append(enc(clientSecret));
        }
        if (scope != null && !scope.isBlank()) {
            sb.append("&scope=").append(enc(scope));
        }
        return sb.toString();
    }

    private AccessToken parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("empty response from OAuth2 token endpoint");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode tokenNode = node.get("access_token");
            if (tokenNode == null || tokenNode.isNull()) {
                throw new IllegalStateException("OAuth2 token response missing access_token");
            }
            long expiresIn = node.has("expires_in") && node.get("expires_in").isNumber()
                    ? node.get("expires_in").asLong() : 0L;
            return new AccessToken(tokenNode.asText(), expiresIn);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse OAuth2 token response: " + e.getMessage(), e);
        }
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
