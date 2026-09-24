package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.harness.tool.rest.CredentialVault;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfGuard;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link McpTransport} 的 HTTP(JSON-RPC) 实现（§7.3）。
 * <p>流程：SSRF 校验 → 凭证/静态头注入 → POST JSON-RPC → 解析响应（兼容 application/json 与
 * text/event-stream 的 {@code data:} 分帧）。同步 {@code block()} 语义交由上层沙箱限时。</p>
 */
public class HttpMcpTransport implements McpTransport {

    private final URI uri;
    private final Map<String, String> headers;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public HttpMcpTransport(String url,
                            Map<String, String> staticHeaders,
                            String credentialRef,
                            List<String> allowedHosts,
                            CredentialVault credentialVault,
                            SsrfGuard ssrfGuard,
                            ObjectMapper objectMapper,
                            WebClient webClient) {
        this.uri = URI.create(url);
        // SSRF 防护：MCP 端点同样禁止访问内网/保留地址，除非显式白名单
        ssrfGuard.validate(uri, allowedHosts);
        this.headers = new HashMap<>();
        if (staticHeaders != null) {
            this.headers.putAll(staticHeaders);
        }
        if (credentialVault != null) {
            this.headers.putAll(credentialVault.resolveHeaders(credentialRef));
        }
        this.objectMapper = objectMapper;
        this.webClient = webClient;
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request) throws Exception {
        String body = objectMapper.writeValueAsString(request);
        String raw = webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(h -> headers.forEach(h::add))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (request.id() == null) {
            return null; // notification 无响应
        }
        String json = extractJson(raw);
        if (json == null || json.isBlank()) {
            throw new McpException("empty response from MCP server: " + uri);
        }
        return objectMapper.readValue(json, JsonRpcResponse.class);
    }

    /** 兼容 SSE 分帧：提取最后一个 {@code data:} 行的 JSON 负载；纯 JSON 响应原样返回。 */
    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.contains("data:")) {
            return trimmed;
        }
        String last = null;
        for (String line : trimmed.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("data:")) {
                String payload = l.substring("data:".length()).trim();
                if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                    last = payload;
                }
            }
        }
        return last;
    }
}
