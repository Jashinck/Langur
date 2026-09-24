package org.skylark.langur.infrastructure.harness.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillToolGateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10c 验收：T 组件将 SKILL 来源工具路由到技能网关，四层校验链无差别生效，网关缺失时安全兜底。
 */
class SkillToolRoutingTest {

    private static final String TOOL_ID = "skill:order-lookup";
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
                .source(ToolSource.SKILL)
                .build());
    }

    private ToolCallRequest request(Map<String, Object> args) {
        return ToolCallRequest.builder().toolId(TOOL_ID).caller("agent-1").traceId("t").arguments(args).build();
    }

    @Test
    void shouldRouteToSkillGatewayAndSucceed() {
        dispatcher.setSkillToolGateway(new SkillToolGateway() {
            @Override public boolean supports(String toolId) { return TOOL_ID.equals(toolId); }
            @Override public String execute(String toolId, Map<String, Object> args) {
                return "skill-done:" + args.get("id");
            }
        });

        ToolCallResult result = dispatcher.dispatch(request(Map.of("id", "42")));

        assertTrue(result.isSuccess());
        assertEquals("skill-done:42", result.getData());
    }

    @Test
    void shouldFailWhenSkillGatewayAbsent() {
        ToolCallResult result = dispatcher.dispatch(request(Map.of("id", "42")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SKILL gateway not available"));
    }

    @Test
    void shouldSurfaceSkillErrorAsFailure() {
        dispatcher.setSkillToolGateway(new SkillToolGateway() {
            @Override public boolean supports(String toolId) { return true; }
            @Override public String execute(String toolId, Map<String, Object> args) {
                throw new IllegalStateException("step tool failed");
            }
        });

        ToolCallResult result = dispatcher.dispatch(request(Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("step tool failed"));
    }
}
