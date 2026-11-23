package org.skylark.langur.infrastructure.harness.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.ToolProvider;
import org.skylark.langur.infrastructure.harness.tool.validator.PermissionToolValidator;
import org.skylark.langur.infrastructure.harness.tool.validator.SandboxToolValidator;
import org.skylark.langur.infrastructure.harness.tool.validator.SchemaToolValidator;
import org.skylark.langur.infrastructure.harness.tool.validator.WhitelistToolValidator;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1 验收 - T 组件四层校验链（白名单→Schema→动态权限→沙箱）+ 沙箱超时熔断。
 */
class DefaultToolDispatcherTest {

    private static final String CALLER = "agent-1";

    private InMemoryToolRegistry registry;
    private DefaultToolDispatcher dispatcher;
    private CountingTool executableTool;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry();
        List<ToolValidator> validators = List.of(
                new WhitelistToolValidator(), new SchemaToolValidator(),
                new PermissionToolValidator(), new SandboxToolValidator());
        executableTool = new CountingTool("http-call");
        ToolProvider provider = () -> List.of(executableTool);
        dispatcher = new DefaultToolDispatcher(registry, validators, List.of(provider));
    }

    @Test
    void shouldRejectUnregisteredTool() {
        ToolCallResult result = dispatcher.dispatch(request("unknown-tool", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not registered"));
        assertEquals(0, executableTool.executions.get());
    }

    @Test
    void shouldRejectCallerOutsideWhitelist() {
        registry.register(definition(List.of("other-agent"), RiskLevel.LOW, null, Duration.ofSeconds(5)));

        ToolCallResult result = dispatcher.dispatch(request("http-call", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not in whitelist"));
        assertEquals(0, executableTool.executions.get());
    }

    @Test
    void shouldRejectMissingRequiredArgument() {
        registry.register(ToolDefinitionEntity.builder()
                .toolId("http-call")
                .inputSchema(Map.of("required", List.of("url")))
                .riskLevel(RiskLevel.LOW)
                .timeout(Duration.ofSeconds(5))
                .source(ToolSource.LOCAL)
                .build());

        ToolCallResult result = dispatcher.dispatch(request("http-call", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("missing required argument"));
        assertEquals(0, executableTool.executions.get());
    }

    @Test
    void shouldRejectHighRiskCallWithoutExplicitPermission() {
        // whitelist 为空（任何调用者可进入白名单层），但高危工具要求显式授权 -> 权限层拒绝
        registry.register(definition(null, RiskLevel.HIGH, null, Duration.ofSeconds(5)));

        ToolCallResult result = dispatcher.dispatch(request("http-call", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("requires explicit permission"));
        assertEquals(0, executableTool.executions.get());
    }

    @Test
    void shouldCircuitBreakWhenSandboxTimeoutExceeded() {
        registry.register(definition(null, RiskLevel.LOW, null, Duration.ofMillis(50)));
        executableTool.delayMillis = 500;

        ToolCallResult result = dispatcher.dispatch(request("http-call", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("timeout"));
    }

    @Test
    void shouldExecuteWhenAllLayersPass() {
        registry.register(definition(List.of(CALLER), RiskLevel.HIGH, null, Duration.ofSeconds(5)));

        ToolCallResult result = dispatcher.dispatch(request("http-call", Map.of("url", "https://x")));

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getData());
        assertEquals(1, executableTool.executions.get());
    }

    private ToolCallRequest request(String toolId, Map<String, Object> arguments) {
        return ToolCallRequest.builder()
                .toolId(toolId)
                .caller(CALLER)
                .traceId("trace-1")
                .arguments(arguments)
                .build();
    }

    private ToolDefinitionEntity definition(List<String> whitelist, RiskLevel risk,
                                            Map<String, Object> schema, Duration timeout) {
        return ToolDefinitionEntity.builder()
                .toolId("http-call")
                .inputSchema(schema)
                .riskLevel(risk)
                .whitelist(whitelist)
                .timeout(timeout)
                .source(ToolSource.LOCAL)
                .build();
    }

    /**
     * 可计数的可执行工具（用于断言"被拒请求不会真正执行"）
     */
    private static class CountingTool extends Tool {
        final AtomicInteger executions = new AtomicInteger();
        volatile long delayMillis = 0;

        CountingTool(String name) {
            super(ToolDefinition.of(name, "test tool", Map.of()));
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            executions.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ToolResult.success("ok");
        }
    }
}
