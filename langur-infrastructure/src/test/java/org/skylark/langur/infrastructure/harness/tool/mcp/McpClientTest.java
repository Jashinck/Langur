package org.skylark.langur.infrastructure.harness.tool.mcp;

import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcError;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10b 验收：{@link McpClient} 在桩传输上完成 initialize 握手、tools/list 发现、tools/call 抽取文本，
 * 并将 JSON-RPC 错误与 isError 负载安全转成 {@link McpException}。
 */
class McpClientTest {

    /** 按 method 返回预置响应的桩传输，并记录最近一次请求。 */
    private static final class StubTransport implements McpTransport {
        private JsonRpcRequest last;
        private final Map<String, JsonRpcResponse> routes;
        private JsonRpcResponse fallback;

        StubTransport(Map<String, JsonRpcResponse> routes) {
            this.routes = routes;
        }

        @Override
        public JsonRpcResponse send(JsonRpcRequest request) {
            this.last = request;
            if (request.id() == null) {
                return null; // notification
            }
            JsonRpcResponse r = routes.get(request.method());
            return r != null ? r : fallback;
        }
    }

    private static JsonRpcResponse ok(Object result) {
        return new JsonRpcResponse("2.0", "id", result, null);
    }

    @Test
    void shouldInitializeAndMarkReady() {
        StubTransport transport = new StubTransport(Map.of(
                "initialize", ok(Map.of("protocolVersion", "2024-11-05"))));
        McpClient client = new McpClient("fs", "2024-11-05", transport);

        assertFalse(client.isInitialized());
        client.initialize();

        assertTrue(client.isInitialized());
    }

    @Test
    void shouldListToolsWithParsedDescriptors() {
        StubTransport transport = new StubTransport(Map.of(
                "tools/list", ok(Map.of("tools", List.of(
                        Map.of("name", "read_file",
                                "description", "Read a file",
                                "inputSchema", Map.of("type", "object")),
                        Map.of("name", "write_file"))))));
        McpClient client = new McpClient("fs", "2024-11-05", transport);

        List<McpToolDescriptor> tools = client.listTools();

        assertEquals(2, tools.size());
        assertEquals("read_file", tools.get(0).name());
        assertEquals("Read a file", tools.get(0).description());
        assertEquals("object", tools.get(0).inputSchema().get("type"));
    }

    @Test
    void shouldCallToolAndExtractText() {
        StubTransport transport = new StubTransport(Map.of(
                "tools/call", ok(Map.of(
                        "content", List.of(
                                Map.of("type", "text", "text", "line1"),
                                Map.of("type", "text", "text", "line2")),
                        "isError", false))));
        McpClient client = new McpClient("fs", "2024-11-05", transport);

        String out = client.callTool("read_file", Map.of("path", "/x"));

        assertEquals("line1\nline2", out);
        assertEquals("tools/call", transport.last.method());
    }

    @Test
    void shouldThrowWhenToolCallReturnsIsError() {
        StubTransport transport = new StubTransport(Map.of(
                "tools/call", ok(Map.of(
                        "content", List.of(Map.of("type", "text", "text", "boom")),
                        "isError", true))));
        McpClient client = new McpClient("fs", "2024-11-05", transport);

        McpException ex = assertThrows(McpException.class,
                () -> client.callTool("read_file", Map.of()));
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void shouldThrowWhenJsonRpcErrorReturned() {
        StubTransport transport = new StubTransport(Map.of(
                "initialize", new JsonRpcResponse("2.0", "id", null,
                        new JsonRpcError(-32601, "method not found", null))));
        McpClient client = new McpClient("fs", "2024-11-05", transport);

        McpException ex = assertThrows(McpException.class, client::initialize);
        assertTrue(ex.getMessage().contains("method not found"));
    }
}
