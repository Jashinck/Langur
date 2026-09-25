package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.InMemoryToolRegistry;
import org.skylark.langur.infrastructure.harness.tool.rest.InMemoryCredentialVault;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfGuard;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10b 验收：{@link McpClientManager} 启动时连接服务端、发现工具并以 {@code mcp:{server}:{tool}}
 * 注册进目录与统一注册中心（source=MCP），includeTools 白名单生效，执行回派 tools/call。
 * <p>H6 追加：{@code tools/list_changed} 通知触发差量热更新（新增注册、下线注销）；
 * 单 server 连续失败熔断跳闸、快速失败，且隔离生效——其余服务端调用不受影响。</p>
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

    /** 可热更新桩：tools/list 返回可变工具集；捕获通知监听器以便测试主动触发 list_changed。 */
    private static final class ReloadableStubTransport implements McpTransport {
        private final List<Map<String, Object>> tools = new ArrayList<>();
        private McpNotificationListener listener;

        ReloadableStubTransport(List<Map<String, Object>> initial) {
            tools.addAll(initial);
        }

        void setTools(List<Map<String, Object>> next) {
            tools.clear();
            tools.addAll(next);
        }

        void fireListChanged() {
            if (listener != null) {
                listener.onNotification("notifications/tools/list_changed", Map.of());
            }
        }

        @Override
        public void setNotificationListener(McpNotificationListener listener) {
            this.listener = listener;
        }

        @Override
        public JsonRpcResponse send(JsonRpcRequest request) {
            if (request.id() == null) {
                return null;
            }
            return switch (request.method()) {
                case "initialize" -> new JsonRpcResponse("2.0", request.id(),
                        Map.of("protocolVersion", "2024-11-05"), null);
                case "tools/list" -> new JsonRpcResponse("2.0", request.id(), Map.of("tools", List.copyOf(tools)), null);
                case "tools/call" -> new JsonRpcResponse("2.0", request.id(),
                        Map.of("content", List.of(Map.of("type", "text", "text", "called")), "isError", false), null);
                default -> new JsonRpcResponse("2.0", request.id(), Map.of(), null);
            };
        }
    }

    /** tools/call 恒失败的桩：握手/发现/心跳正常，仅调用阶段抛错，用于验证熔断。 */
    private static final class FailingCallTransport implements McpTransport {
        @Override
        public JsonRpcResponse send(JsonRpcRequest request) {
            if (request.id() == null) {
                return null;
            }
            return switch (request.method()) {
                case "initialize" -> new JsonRpcResponse("2.0", request.id(),
                        Map.of("protocolVersion", "2024-11-05"), null);
                case "tools/list" -> new JsonRpcResponse("2.0", request.id(),
                        Map.of("tools", List.of(Map.of("name", "read_file", "description", "Read"))), null);
                case "tools/call" -> throw new McpException("boom: server down");
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

    @Test
    void shouldHotReloadToolsOnListChangedNotification() {
        McpToolCatalog catalog = new McpToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        ReloadableStubTransport transport = new ReloadableStubTransport(List.of(
                Map.of("name", "read_file", "description", "Read")));
        McpToolProperties props = props(true, List.of());
        McpClientManager manager = new McpClientManager(props, catalog, registry,
                new InMemoryCredentialVault(), new SsrfGuard(), new ObjectMapper()) {
            @Override
            protected McpTransport newTransport(McpToolProperties.ServerProps server) {
                return transport;
            }
        };
        manager.init();

        assertTrue(catalog.find("mcp:fs:read_file").isPresent());
        assertFalse(catalog.find("mcp:fs:write_file").isPresent());

        // 远端新增工具并推送 list_changed → 差量注册
        transport.setTools(List.of(
                Map.of("name", "read_file", "description", "Read"),
                Map.of("name", "write_file", "description", "Write")));
        transport.fireListChanged();
        assertTrue(catalog.find("mcp:fs:write_file").isPresent());
        assertTrue(registry.find("mcp:fs:write_file").isPresent());

        // 远端下线 write_file 并再次推送 → 差量注销（目录与注册中心同步移除）
        transport.setTools(List.of(Map.of("name", "read_file", "description", "Read")));
        transport.fireListChanged();
        assertFalse(catalog.find("mcp:fs:write_file").isPresent());
        assertFalse(registry.find("mcp:fs:write_file").isPresent());
        assertTrue(catalog.find("mcp:fs:read_file").isPresent());
        manager.shutdown();
    }

    @Test
    void shouldIsolateFailingServerViaCircuitBreaker() {
        McpToolCatalog catalog = new McpToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        McpToolProperties props = new McpToolProperties();
        props.setEnabled(true);
        props.setHeartbeatSeconds(0);
        props.setFailureThreshold(2);
        props.setCircuitCooldownSeconds(60);
        McpToolProperties.ServerProps good = new McpToolProperties.ServerProps();
        good.setName("good");
        good.setUrl("https://good.example.com/rpc");
        good.setTimeoutSeconds(10);
        McpToolProperties.ServerProps bad = new McpToolProperties.ServerProps();
        bad.setName("bad");
        bad.setUrl("https://bad.example.com/rpc");
        bad.setTimeoutSeconds(10);
        props.setServers(List.of(good, bad));

        McpClientManager manager = new McpClientManager(props, catalog, registry,
                new InMemoryCredentialVault(), new SsrfGuard(), new ObjectMapper()) {
            @Override
            protected McpTransport newTransport(McpToolProperties.ServerProps server) {
                return "bad".equals(server.getName()) ? new FailingCallTransport() : new StubTransport();
            }
        };
        manager.init();

        // 正常服务端可用
        assertEquals("read:/etc/hosts", manager.execute("mcp:good:read_file", Map.of("path", "/etc/hosts")));

        // 故障服务端连续失败达阈值 → 熔断跳闸
        for (int i = 0; i < 2; i++) {
            assertThrows(McpException.class,
                    () -> manager.execute("mcp:bad:read_file", Map.of("path", "/x")));
        }
        assertTrue(manager.circuitOpen("bad"));

        // 跳闸后快速失败（异常信息含 circuit open），且不影响正常服务端
        McpException fast = assertThrows(McpException.class,
                () -> manager.execute("mcp:bad:read_file", Map.of("path", "/x")));
        assertTrue(fast.getMessage().contains("circuit open"));
        assertFalse(manager.circuitOpen("good"));
        assertEquals("read:/etc/hosts", manager.execute("mcp:good:read_file", Map.of("path", "/etc/hosts")));
        manager.shutdown();
    }
}
