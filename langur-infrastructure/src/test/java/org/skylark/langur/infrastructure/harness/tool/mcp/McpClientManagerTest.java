package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.InMemoryToolRegistry;
import org.skylark.langur.infrastructure.harness.tool.rest.InMemoryCredentialVault;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfGuard;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10b 验收：{@link McpClientManager} 启动时连接服务端、发现工具并以 {@code mcp:{server}:{tool}}
 * 注册进目录与统一注册中心（source=MCP），includeTools 白名单生效，执行回派 tools/call。
 */
class McpClientManagerTest {

    /** 桩传输：initialize 成功、tools/list 返回两个工具、tools/call 回显路径。 */
    private static final class StubTransport implements McpTransport {
        @Override
        public JsonRpcResponse send(JsonRpcRequest request) {
            if (request.id() == null) {
                return null;
            }
            return switch (request.method()) {
                case "initialize" -> new JsonRpcResponse("2.0", request.id(),
                        Map.of("protocolVersion", "2024-11-05"), null);
                case "tools/list" -> new JsonRpcResponse("2.0", request.id(),
                        Map.of("tools", List.of(
                                Map.of("name", "read_file", "description", "Read",
                                        "inputSchema", Map.of("type", "object")),
                                Map.of("name", "write_file", "description", "Write"))), null);
                case "tools/call" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) request.params();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = (Map<String, Object>) params.get("arguments");
                    yield new JsonRpcResponse("2.0", request.id(),
                            Map.of("content", List.of(Map.of("type", "text",
                                    "text", "read:" + args.get("path"))), "isError", false), null);
                }
                case "ping" -> new JsonRpcResponse("2.0", request.id(), Map.of(), null);
                default -> new JsonRpcResponse("2.0", request.id(), Map.of(), null);
            };
        }
    }

    private McpClientManager newManager(McpToolProperties props, McpToolCatalog catalog,
                                        InMemoryToolRegistry registry) {
        return new McpClientManager(props, catalog, registry,
                new InMemoryCredentialVault(), new SsrfGuard(), new ObjectMapper()) {
            @Override
            protected McpTransport newTransport(McpToolProperties.ServerProps server) {
                return new StubTransport();
            }
        };
    }

    private McpToolProperties props(boolean enabled, List<String> includeTools) {
        McpToolProperties props = new McpToolProperties();
        props.setEnabled(enabled);
        props.setHeartbeatSeconds(0); // 关闭心跳调度，避免测试残留线程
        McpToolProperties.ServerProps server = new McpToolProperties.ServerProps();
        server.setName("fs");
        server.setUrl("https://mcp.example.com/rpc");
        server.setRiskLevel("LOW");
        server.setTimeoutSeconds(10);
        server.setIncludeTools(includeTools);
        props.setServers(List.of(server));
        return props;
    }

    @Test
    void shouldDiscoverAndRegisterToolsOnConnect() {
        McpToolCatalog catalog = new McpToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        McpClientManager manager = newManager(props(true, List.of()), catalog, registry);

        manager.init();

        assertTrue(catalog.find("mcp:fs:read_file").isPresent());
        assertTrue(registry.find("mcp:fs:read_file").isPresent());
        assertEquals(ToolSource.MCP, registry.find("mcp:fs:read_file").get().getSource());
        manager.shutdown();
    }

    @Test
    void shouldHonorIncludeToolsWhitelist() {
        McpToolCatalog catalog = new McpToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        McpClientManager manager = newManager(props(true, List.of("read_file")), catalog, registry);

        manager.init();

        assertTrue(catalog.find("mcp:fs:read_file").isPresent());
        assertFalse(catalog.find("mcp:fs:write_file").isPresent());
        manager.shutdown();
    }

    @Test
    void shouldExecuteDiscoveredToolViaGateway() {
        McpToolCatalog catalog = new McpToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        McpClientManager manager = newManager(props(true, List.of()), catalog, registry);
        manager.init();

        assertTrue(manager.supports("mcp:fs:read_file"));
        String out = manager.execute("mcp:fs:read_file", Map.of("path", "/etc/hosts"));

        assertEquals("read:/etc/hosts", out);
        manager.shutdown();
    }

    @Test
    void shouldStayIdleWhenDisabled() {
        McpToolCatalog catalog = new McpToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        McpClientManager manager = newManager(props(false, List.of()), catalog, registry);

        manager.init();

        assertTrue(catalog.all().isEmpty());
        assertFalse(manager.supports("mcp:fs:read_file"));
        manager.shutdown();
    }
}
