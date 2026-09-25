package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.rest.CredentialVault;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP 客户端管理器（§7.3）- 负责 MCP 服务端的连接、工具发现、心跳保活与断线重连，
 * 并作为 {@link McpToolGateway} 供 T 组件调度器在四层校验通过后调用。
 * <p>启动阶段（{@code @PostConstruct}）对已启用配置逐个连接并发现工具，注册进 {@link ToolRegistry}
 * （source=MCP，工具 ID {@code mcp:{server}:{tool}}）；连接失败仅告警降级，不阻断应用启动。</p>
 * <p>H6 补全：支持 STDIO/SSE/WS 双向传输（HTTP 之外），订阅 {@code tools/list_changed} 热更新，
 * 并按服务端维度熔断隔离——单 server 故障仅隔离自身，其余服务端调用不受影响。</p>
 */
@Slf4j
@Component
public class McpClientManager implements McpToolGateway {

    private final McpToolProperties properties;
    private final McpToolCatalog catalog;
    private final ToolRegistry toolRegistry;
    private final CredentialVault credentialVault;
    private final SsrfGuard ssrfGuard;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    public McpClientManager(McpToolProperties properties,
                            McpToolCatalog catalog,
                            ToolRegistry toolRegistry,
                            CredentialVault credentialVault,
                            SsrfGuard ssrfGuard,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.catalog = catalog;
        this.toolRegistry = toolRegistry;
        this.credentialVault = credentialVault;
        this.ssrfGuard = ssrfGuard;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[T] MCP tool source disabled (langur.mcp-tools.enabled=false)");
            return;
        }
        properties.getServers().forEach(this::connect);
        startHeartbeat();
        log.info("[T] MCP tool source enabled: {} server(s), {} tool(s) discovered",
                properties.getServers().size(), catalog.all().size());
    }

    @PreDestroy
    public void shutdown() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        connections.values().forEach(connection -> {
            if (connection.transport != null) {
                try {
                    connection.transport.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败无需上抛
                }
            }
        });
    }

    /** 连接一个 MCP 服务端并完成工具发现；失败降级为未连接状态，后续调用触发懒重连。 */
    private void connect(McpToolProperties.ServerProps server) {
        McpTransport transport = null;
        try {
            transport = newTransport(server);
            String serverName = server.getName();
            transport.setNotificationListener((method, params) -> onNotification(serverName, method));
            McpClient client = new McpClient(serverName, properties.getProtocolVersion(), transport);
            client.initialize();
            Connection connection = new Connection(server, client, transport, newBreaker(server));
            connection.connected = true;
            connections.put(serverName, connection);
            discover(connection);
            log.info("[T] MCP server [{}] connected via {}", serverName, server.getTransport());
        } catch (Exception e) {
            closeQuietly(transport);
            log.warn("[T] MCP server [{}] connect failed, will retry lazily: {}", server.getName(), e.getMessage());
            connections.computeIfAbsent(server.getName(),
                    name -> new Connection(server, null, null, newBreaker(server)));
        }
    }

    private ServerCircuitBreaker newBreaker(McpToolProperties.ServerProps server) {
        return new ServerCircuitBreaker(server.getName(), properties.getFailureThreshold(),
                properties.getCircuitCooldownSeconds() * 1000L);
    }

    /** 服务端通知：{@code tools/list_changed} 触发工具热更新（重新发现并差量注册/注销）。 */
    private void onNotification(String serverName, String method) {
        if (method == null) {
            return;
        }
        if (method.endsWith("tools/list_changed")) {
            log.info("[T] MCP server [{}] notified tools/list_changed, refreshing catalog", serverName);
            refresh(serverName);
        }
    }

    /** tools/list 发现工具并注册进目录与统一注册中心。 */
    private void discover(Connection connection) {
        registerDiscovered(connection, connection.client.listTools());
    }

    /**
     * 热更新（H6）：重新发现某服务端工具，差量注册新增、注销已下线者。
     * 失败降级为保留现状（P10），不影响其余服务端。
     */
    private void refresh(String serverName) {
        Connection connection = connections.get(serverName);
        if (connection == null || connection.client == null) {
            return;
        }
        try {
            List<McpToolDescriptor> tools = connection.client.listTools();
            Set<String> current = registerDiscovered(connection, tools);
            // 差量注销：目录中属于该服务端但本次未发现者，视为远端已下线
            for (McpToolSpec stale : catalog.byServer(serverName)) {
                if (!current.contains(stale.toolId())) {
                    catalog.unregister(stale.toolId());
                    toolRegistry.unregister(stale.toolId());
                    log.info("[T] unregistered stale MCP tool on refresh: {}", stale.toolId());
                }
            }
        } catch (Exception e) {
            log.warn("[T] MCP server [{}] refresh failed, keeping current catalog: {}", serverName, e.getMessage());
        }
    }

    /** 依据 includeTools 白名单注册发现的工具，返回本次在册工具 ID 集合。 */
    private Set<String> registerDiscovered(Connection connection, List<McpToolDescriptor> tools) {
        List<String> include = connection.server.getIncludeTools();
        Set<String> ids = new LinkedHashSet<>();
        for (McpToolDescriptor descriptor : tools) {
            if (include != null && !include.isEmpty() && !include.contains(descriptor.name())) {
                continue;
            }
            McpToolSpec spec = buildSpec(connection.server, descriptor);
            catalog.register(spec);
            toolRegistry.register(toDefinition(spec));
            ids.add(spec.toolId());
            log.info("[T] registered MCP tool into unified registry: {}", spec.toolId());
        }
        return ids;
    }

    private McpToolSpec buildSpec(McpToolProperties.ServerProps server, McpToolDescriptor descriptor) {
        return McpToolSpec.builder()
                .server(server.getName())
                .name(descriptor.name())
                .description(descriptor.description())
                .inputSchema(descriptor.inputSchema())
                .permission(server.getPermission())
                .riskLevel(server.getRiskLevel())
                .allowedHosts(server.getAllowedHosts())
                .timeout(Duration.ofSeconds(server.getTimeoutSeconds()))
                .build();
    }

    private ToolDefinitionEntity toDefinition(McpToolSpec spec) {
        return ToolDefinitionEntity.builder()
                .toolId(spec.toolId())
                .description(spec.getDescription() != null ? spec.getDescription() : "MCP tool " + spec.toolId())
                .inputSchema(spec.getInputSchema())
                .outputSchema(null)
                .permission(spec.getPermission())
                .riskLevel(parseRisk(spec.getRiskLevel()))
                .whitelist(List.of())
                .rateLimitPerMinute(null)
                .timeout(spec.getTimeout())
                .source(ToolSource.MCP)
                .build();
    }

    private void startHeartbeat() {
        long interval = properties.getHeartbeatSeconds();
        if (interval <= 0 || connections.isEmpty()) {
            return;
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(this::heartbeatOnce, interval, interval, TimeUnit.SECONDS);
    }

    private void heartbeatOnce() {
        connections.values().forEach(connection -> {
            try {
                if (connection.connected && connection.client != null) {
                    connection.client.ping();
                    connection.breaker.recordSuccess();
                }
            } catch (Exception e) {
                log.warn("[T] MCP heartbeat failed on [{}], reconnecting: {}",
                        connection.server.getName(), e.getMessage());
                connection.breaker.recordFailure();
                reconnect(connection);
            }
        });
    }

    private void reconnect(Connection connection) {
        connection.connected = false;
        closeQuietly(connection.transport);
        connect(connection.server);
    }

    /** 确保服务端连接可用（懒重连），返回就绪的客户端。 */
    private McpClient ensureClient(String server) {
        Connection connection = connections.get(server);
        if (connection == null) {
            throw new McpException("unknown MCP server: " + server);
        }
        if (!connection.connected || connection.client == null) {
            reconnect(connection);
            Connection refreshed = connections.get(server);
            if (refreshed == null || !refreshed.connected || refreshed.client == null) {
                throw new McpException("MCP server not connected: " + server);
            }
            return refreshed.client;
        }
        return connection.client;
    }

    /**
     * 传输层工厂（H6）- 按 {@link McpTransportType} 选择实现；独立成方法便于单测以桩替换，规避真实网络/进程。
     */
    protected McpTransport newTransport(McpToolProperties.ServerProps server) {
        McpTransportType type = server.getTransport() != null ? server.getTransport() : McpTransportType.HTTP;
        long timeoutMillis = server.getTimeoutSeconds() * 1000L;
        return switch (type) {
            case HTTP -> new HttpMcpTransport(server.getUrl(), server.getHeaders(), server.getCredentialRef(),
                    server.getAllowedHosts(), credentialVault, ssrfGuard, objectMapper, webClient);
            case STDIO -> StdioMcpTransport.launch(server.getCommand(), server.getEnv(), objectMapper, timeoutMillis);
            case SSE -> {
                URI postUri = URI.create(server.getUrl());
                URI sseUri = URI.create(server.getSseUrl() != null && !server.getSseUrl().isBlank()
                        ? server.getSseUrl() : server.getUrl());
                ssrfGuard.validate(postUri, server.getAllowedHosts());
                ssrfGuard.validate(sseUri, server.getAllowedHosts());
                yield SseMcpTransport.connect(webClient, sseUri, postUri, resolveHeaders(server),
                        objectMapper, timeoutMillis);
            }
            case WS -> {
                URI httpUri = URI.create(server.getUrl());
                ssrfGuard.validate(httpUri, server.getAllowedHosts());
                yield WebSocketMcpTransport.connect(httpClient, toWebSocketUri(httpUri), resolveHeaders(server),
                        objectMapper, timeoutMillis);
            }
        };
    }

    /** 静态头 + 凭证库解析头（密钥不出库、不落日志）。 */
    private Map<String, String> resolveHeaders(McpToolProperties.ServerProps server) {
        Map<String, String> headers = new HashMap<>();
        if (server.getHeaders() != null) {
            headers.putAll(server.getHeaders());
        }
        if (credentialVault != null) {
            headers.putAll(credentialVault.resolveHeaders(server.getCredentialRef()));
        }
        return headers;
    }

    /** http(s) → ws(s)，供 SSRF 校验后建立 WebSocket。 */
    private URI toWebSocketUri(URI httpUri) {
        String scheme = "https".equalsIgnoreCase(httpUri.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + httpUri.getRawAuthority() + httpUri.getRawPath()
                + (httpUri.getRawQuery() != null ? "?" + httpUri.getRawQuery() : ""));
    }

    private RiskLevel parseRisk(String risk) {
        try {
            return risk == null ? RiskLevel.LOW : RiskLevel.valueOf(risk.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.LOW;
        }
    }

    private void closeQuietly(McpTransport transport) {
        if (transport != null) {
            try {
                transport.close();
            } catch (RuntimeException ignored) {
                // 关闭失败无需上抛
            }
        }
    }

    // ---- McpToolGateway ----

    @Override
    public boolean supports(String toolId) {
        return catalog.find(toolId).isPresent();
    }

    @Override
    public String execute(String toolId, Map<String, Object> arguments) {
        McpToolSpec spec = catalog.find(toolId)
                .orElseThrow(() -> new McpException("MCP tool spec not found: " + toolId));
        Connection connection = connections.get(spec.getServer());
        if (connection != null) {
            connection.breaker.checkOpen();
        }
        try {
            McpClient client = ensureClient(spec.getServer());
            String out = client.callTool(spec.getName(), arguments);
            if (connection != null) {
                connection.breaker.recordSuccess();
            }
            return out;
        } catch (RuntimeException e) {
            if (connection != null) {
                connection.breaker.recordFailure();
            }
            throw e;
        }
    }

    Optional<Connection> connection(String server) {
        return Optional.ofNullable(connections.get(server));
    }

    /** 供单测观察熔断隔离状态。 */
    boolean circuitOpen(String server) {
        Connection connection = connections.get(server);
        return connection != null && connection.breaker.isOpen();
    }

    /** 单个 MCP 服务端连接状态（H6：持有传输句柄与独立熔断器）。 */
    private static final class Connection {
        private final McpToolProperties.ServerProps server;
        private final McpTransport transport;
        private final ServerCircuitBreaker breaker;
        private McpClient client;
        private volatile boolean connected;

        private Connection(McpToolProperties.ServerProps server, McpClient client,
                           McpTransport transport, ServerCircuitBreaker breaker) {
            this.server = server;
            this.client = client;
            this.transport = transport;
            this.breaker = breaker;
        }
    }
}
