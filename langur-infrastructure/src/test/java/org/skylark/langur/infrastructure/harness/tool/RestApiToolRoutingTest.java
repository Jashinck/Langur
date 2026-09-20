package org.skylark.langur.infrastructure.harness.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.rest.RestApiToolGateway;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfViolationException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10a 验收：T 组件将 REST_API 来源工具路由到网关，四层校验链无差别生效，网关异常/SSRF 被安全兜底。
 */
class RestApiToolRoutingTest {

    private static final String TOOL_ID = "api:demo:get";
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
                .source(ToolSource.REST_API)
                .build());
    }

    private ToolCallRequest request(Map<String, Object> args) {
        return ToolCallRequest.builder().toolId(TOOL_ID).caller("agent-1").traceId("t").arguments(args).build();
    }

    @Test
    void shouldRouteToGatewayAndSucceed() {
        dispatcher.setRestApiToolGateway(new RestApiToolGateway() {
            @Override public boolean supports(String toolId) { return TOOL_ID.equals(toolId); }
            @Override public String execute(String toolId, Map<String, Object> args) { return "resp:" + args.get("q"); }
        });

        ToolCallResult result = dispatcher.dispatch(request(Map.of("q", "x")));

        assertTrue(result.isSuccess());
        assertEquals("resp:x", result.getData());
    }

    @Test
    void shouldFailWhenGatewayAbsent() {
        ToolCallResult result = dispatcher.dispatch(request(Map.of("q", "x")));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("gateway not available"));
    }

    @Test
    void shouldSurfaceSsrfViolationAsFailure() {
        dispatcher.setRestApiToolGateway(new RestApiToolGateway() {
            @Override public boolean supports(String toolId) { return true; }
            @Override public String execute(String toolId, Map<String, Object> args) {
                throw new SsrfViolationException("address in reserved/internal range: 10.0.0.5");
            }
        });

        ToolCallResult result = dispatcher.dispatch(request(Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("reserved/internal range"));
    }
}
