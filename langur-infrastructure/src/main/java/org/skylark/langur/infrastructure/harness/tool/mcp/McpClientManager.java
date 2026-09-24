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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP 客户端管理器（§7.3）- 负责 MCP 服务端的连接、工具发现、心跳保活与断线重连，
 * 并作为 {@link McpToolGateway} 供 T 组件调度器在四层校验通过后调用。
 * <p>启动阶段（{@code @PostConstruct}）对已启用配置逐个连接并发现工具，注册进 {@link ToolRegistry}
 * （source=MCP，工具 ID {@code mcp:{server}:{tool}}）；连接失败仅告警降级，不阻断应用启动。</p>
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
    }

    /** 连接一个 MCP 服务端并完成工具发现；失败降级为未连接状态，后续调用触发懒重连。 */
    private void connect(McpToolProperties.ServerProps server) {
        try {
            McpTransport transport = newTransport(server);
            McpClient client = new McpClient(server.getName(), properties.getProtocolVersion(), transport);
            client.initialize();
            Connection connection = new Connection(server, client);
            connection.connected = true;
            connections.put(server.getName(), connection);
            discover(connection);
            log.info("[T] MCP server [{}] connected", server.getName());
        } catch (Exception e) {
            log.warn("[T] MCP server [{}] connect failed, will retry lazily: {}", server.getName(), e.getMessage());
            connections.put(server.getName(), new Connection(server, null));
        }
    }

    /** tools/list 发现工具并注册进目录与统一注册中心。 */
    private void discover(Connection connection) {
        List<McpToolDescriptor> tools = connection.client.listTools();
        List<String> include = connection.server.getIncludeTools();
        for (McpToolDescriptor descriptor : tools) {
            if (include != null && !include.isEmpty() && !include.contains(descriptor.name())) {
                continue;
            }
            McpToolSpec spec = McpToolSpec.builder()
                    .server(connection.server.getName())
                    .name(descriptor.name())
                    .description(descriptor.description())
                    .inputSchema(descriptor.inputSchema())
                    .permission(connection.server.getPermission())
                    .riskLevel(connection.server.getRiskLevel())
                    .allowedHosts(connection.server.getAllowedHosts())
                    .timeout(Duration.ofSeconds(connection.server.getTimeoutSeconds()))
                    .build();
            catalog.register(spec);
            toolRegistry.register(toDefinition(spec));
            log.info("[T] registered MCP tool into unified registry: {}", spec.toolId());
        }
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
                }
            } catch (Exception e) {
                log.warn("[T] MCP heartbeat failed on [{}], reconnecting: {}",
                        connection.server.getName(), e.getMessage());
                reconnect(connection);
            }
        });
    }

    private void reconnect(Connection connection) {
        connection.connected = false;
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

    /** 传输层工厂 - 独立成方法便于单测以桩替换，规避真实网络。 */
    protected McpTransport newTransport(McpToolProperties.ServerProps server) {
        return new HttpMcpTransport(server.getUrl(), server.getHeaders(), server.getCredentialRef(),
                server.getAllowedHosts(), credentialVault, ssrfGuard, objectMapper, webClient);
    }

    private RiskLevel parseRisk(String risk) {
        try {
            return risk == null ? RiskLevel.LOW : RiskLevel.valueOf(risk.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.LOW;
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
        McpClient client = ensureClient(spec.getServer());
        return client.callTool(spec.getName(), arguments);
    }

    Optional<Connection> connection(String server) {
        return Optional.ofNullable(connections.get(server));
    }

    /** 单个 MCP 服务端连接状态。 */
    private static final class Connection {
        private final McpToolProperties.ServerProps server;
        private McpClient client;
        private volatile boolean connected;

        private Connection(McpToolProperties.ServerProps server, McpClient client) {
            this.server = server;
            this.client = client;
        }
    }
}
