package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.rsi.ToolExtensionCandidate;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 工具自扩展注册器测试（无 Mockito，匿名桩 ToolRegistry）。
 * <p>覆盖：放行候选注册、越权/内网候选不注册、高危未经人审不注册、注销。</p>
 */
class ToolExtensionRegistrarTest {

    private static final class StubRegistry implements ToolRegistry {
        final ConcurrentMap<String, ToolDefinitionEntity> defs = new ConcurrentHashMap<>();

        @Override
        public void register(ToolDefinitionEntity definition) {
            defs.put(definition.getToolId(), definition);
        }

        @Override
        public Optional<ToolDefinitionEntity> find(String toolId) {
            return Optional.ofNullable(defs.get(toolId));
        }

        @Override
        public List<ToolDefinitionEntity> listVisible(String caller) {
            return new ArrayList<>(defs.values());
        }

        @Override
        public void unregister(String toolId) {
            defs.remove(toolId);
        }
    }

    private final ToolExtensionRegistrar registrar = new ToolExtensionRegistrar();

    @Test
    void shouldRegisterSafeCandidate() {
        StubRegistry registry = new StubRegistry();
        boolean registered = registrar.register(
                ToolExtensionCandidate.of("t1", "rest", "https://api.example.com", "d", "LOW", "邮件"),
                false, registry);

        assertTrue(registered);
        assertTrue(registry.find("t1").isPresent());
    }

    @Test
    void shouldNotRegisterUnsafeEndpoint() {
        StubRegistry registry = new StubRegistry();
        boolean registered = registrar.register(
                ToolExtensionCandidate.of("t1", "rest", "http://127.0.0.1:8080", "d", "LOW", "邮件"),
                false, registry);

        assertFalse(registered, "内网/环回候选被 SSRF 层拒绝");
        assertTrue(registry.find("t1").isEmpty());
    }

    @Test
    void shouldNotRegisterHighRiskWithoutApproval() {
        StubRegistry registry = new StubRegistry();
        ToolExtensionCandidate high = ToolExtensionCandidate.of("t1", "mcp", "https://api.example.com", "d", "HIGH", "x");

        assertFalse(registrar.register(high, false, registry), "未审批工具不生效");
        assertTrue(registrar.register(high, true, registry), "审批通过后注册");
    }

    @Test
    void shouldUnregister() {
        StubRegistry registry = new StubRegistry();
        registrar.register(ToolExtensionCandidate.of("t1", "rest", "https://api.example.com", "d", "LOW", "x"),
                false, registry);

        registrar.unregister("t1", registry);

        assertTrue(registry.find("t1").isEmpty());
    }
}
