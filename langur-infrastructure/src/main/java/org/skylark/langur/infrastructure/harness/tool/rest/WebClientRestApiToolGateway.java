package org.skylark.langur.infrastructure.harness.tool.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RestApiToolGateway} 的 WebClient 实现（§7.4）。
 * <p>流程：URL 模板占位符替换 → 剩余参数转 query/body → SSRF 校验 → 凭证头注入 → HTTP 执行。</p>
 */
@Slf4j
@Component
public class WebClientRestApiToolGateway implements RestApiToolGateway {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final RestApiToolCatalog catalog;
    private final CredentialVault credentialVault;
    private final SsrfGuard ssrfGuard;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WebClientRestApiToolGateway(RestApiToolCatalog catalog,
                                       CredentialVault credentialVault,
                                       SsrfGuard ssrfGuard,
                                       ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.credentialVault = credentialVault;
        this.ssrfGuard = ssrfGuard;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public boolean supports(String toolId) {
        return catalog.find(toolId).isPresent();
    }

    @Override
    public String execute(String toolId, Map<String, Object> arguments) throws Exception {
        RestApiToolSpec spec = catalog.find(toolId)
                .orElseThrow(() -> new IllegalArgumentException("REST API tool spec not found: " + toolId));
        Map<String, Object> args = arguments != null ? arguments : Map.of();

        Map<String, Object> remaining = new LinkedHashMap<>(args);
        String resolvedUrl = resolveTemplate(spec.getUrlTemplate(), remaining);
        boolean hasBody = !isGet(spec.getMethod()) && !remaining.isEmpty();
        URI uri = appendQuery(resolvedUrl, hasBody ? Map.of() : remaining);

        // SSRF 防护：解析后的最终地址必须为公网或显式白名单
        ssrfGuard.validate(uri, spec.getAllowedHosts());

        Map<String, String> headers = new HashMap<>();
        if (spec.getHeaders() != null) {
            headers.putAll(spec.getHeaders());
        }
        headers.putAll(credentialVault.resolveHeaders(spec.getCredentialRef()));

        String body = hasBody ? objectMapper.writeValueAsString(remaining) : null;
        HttpMethod method = HttpMethod.valueOf(spec.getMethod() == null ? "GET" : spec.getMethod().toUpperCase());

        WebClient.RequestBodySpec requestSpec = webClient.method(method)
                .uri(uri)
                .headers(h -> headers.forEach(h::add));

        WebClient.RequestHeadersSpec<?> readySpec = body != null
                ? requestSpec.bodyValue(body)
                : requestSpec;

        return readySpec.retrieve().bodyToMono(String.class).block();
    }

    /** 替换 urlTemplate 中的 {param} 占位符，并从 remaining 移除已消费参数。 */
    private String resolveTemplate(String template, Map<String, Object> remaining) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            Object value = remaining.remove(key);
            String encoded = value == null ? ""
                    : URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 将剩余参数作为 query 追加到 URL（GET 或无 body 场景）。 */
    private URI appendQuery(String url, Map<String, Object> query) {
        if (query == null || query.isEmpty()) {
            return URI.create(url);
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    private boolean isGet(String method) {
        return method == null || method.equalsIgnoreCase("GET");
    }
}
