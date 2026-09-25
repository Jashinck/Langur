package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H6 验收：{@link SseMcpTransport} 出站经 POST、入站持久事件流按 id 关联响应并派发服务端通知。
 * 以出站接缝（录制型 {@link McpSender}）+ 直接 {@code deliver} 模拟事件流帧，脱离真实 SSE 连接。
 */
class SseMcpTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldPostOutboundAndCorrelateDeliveredResponse() throws Exception {
        AtomicReference<SseMcpTransport> ref = new AtomicReference<>();
        List<String> posted = Collections.synchronizedList(new ArrayList<>());
        SseMcpTransport transport = new SseMcpTransport(json -> {
            posted.add(json);
            JsonRpcRequest req = mapper.readValue(json, JsonRpcRequest.class);
            if (req.id() != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        ref.get().deliver(mapper.writeValueAsString(new JsonRpcResponse("2.0", req.id(),
                                Map.of("content", List.of(Map.of("type", "text", "text", "sse-ok"))), null)));
                    } catch (Exception ignored) {
                        // 序列化失败在断言处暴露
                    }
                });
            }
        }, mapper, 5_000);
        ref.set(transport);

        JsonRpcResponse response = transport.send(JsonRpcRequest.of("1", "tools/call", Map.of("name", "x")));

        assertNotNull(response);
        assertFalse(response.isError());
        assertEquals(1, posted.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("sse-ok", content.get(0).get("text"));
        transport.close();
    }

    @Test
    void shouldDispatchInboundNotification() throws Exception {
        SseMcpTransport transport = new SseMcpTransport(json -> {
        }, mapper, 5_000);
        List<String> methods = new ArrayList<>();
        transport.setNotificationListener((method, params) -> methods.add(method));

        transport.deliver(mapper.writeValueAsString(
                JsonRpcRequest.notification("notifications/tools/list_changed", Map.of())));

        assertTrue(methods.stream().anyMatch(m -> m.endsWith("tools/list_changed")));
    }
}
