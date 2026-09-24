package org.skylark.langur.infrastructure.harness.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.mcp.McpToolGateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10b 验收：T 组件将 MCP 来源工具路由到网关，四层校验链无差别生效，网关缺失时安全兜底。
 */
class McpToolRoutingTest {

    private static final String TOOL_ID = "mcp:fs:read_file";
    private InMemoryToolRegistry registry;
    private DefaultToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry();
        dispatcher = new DefaultToolDispatcher(registry, List.of(), List.of());
        registry.register(ToolDefinitionEntity.builder()
                .toolId(TOOL_ID)
                .riskLevel(RiskLevel.LOW)
                .timeout(Duration.ofSeconds(5))
                .source(ToolSource.MCP)
                .build());
    }

    private ToolCallRequest request(Map<String, Object> args) {
        return ToolCallRequest.builder().toolId(TOOL_ID).caller("agent-1").traceId("t").arguments(args).build();
    }

    @Test
    void shouldRouteToMcpGatewayAndSucceed() {
        dispatcher.setMcpToolGateway(new McpToolGateway() {
            @Override public boolean supports(String toolId) { return TOOL_ID.equals(toolId); }
            @Override public String execute(String toolId, Map<String, Object> args) {
                return "content:" + args.get("path");
            }
        });

        ToolCallResult result = dispatcher.dispatch(request(Map.of("path", "/x")));

        assertTrue(result.isSuccess());
        assertEquals("content:/x", result.getData());
    }

    @Test
    void shouldFailWhenMcpGatewayAbsent() {
        ToolCallResult result = dispatcher.dispatch(request(Map.of("path", "/x")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("MCP gateway not available"));
    }

    @Test
    void shouldSurfaceMcpErrorAsFailure() {
        dispatcher.setMcpToolGateway(new McpToolGateway() {
            @Override public boolean supports(String toolId) { return true; }
            @Override public String execute(String toolId, Map<String, Object> args) {
                throw new IllegalStateException("server not connected");
            }
        });

        ToolCallResult result = dispatcher.dispatch(request(Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("server not connected"));
    }
}
