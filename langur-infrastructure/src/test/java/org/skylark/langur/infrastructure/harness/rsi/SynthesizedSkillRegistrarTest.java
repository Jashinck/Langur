package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillCatalog;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3 合成技能注册器测试（无 Mockito，真实 {@link SkillCatalog} + 匿名桩 {@link ToolRegistry}）。
 * <p>覆盖：注册进目录+注册中心、版本计数、回滚恢复上一版本、回滚到空即注销、无历史回滚返回 false。</p>
 */
class SynthesizedSkillRegistrarTest {

    private static SkillSpec spec(String name, String description) {
        return SkillSpec.builder().name(name).description(description)
                .permission("").riskLevel("LOW").inputSchema(java.util.Map.of()).steps(List.of()).build();
    }

    private static final class StubToolRegistry implements ToolRegistry {
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

    @Test
    void shouldRegisterIntoCatalogAndRegistry() {
        SkillCatalog catalog = new SkillCatalog();
        StubToolRegistry registry = new StubToolRegistry();
        SynthesizedSkillRegistrar registrar = new SynthesizedSkillRegistrar();

        registrar.register(spec("research", "v1"), catalog, registry);

        assertTrue(catalog.find("skill:research").isPresent());
        assertTrue(registry.find("skill:research").isPresent());
        assertEquals(1, registrar.versionCount("skill:research"));
    }

    @Test
    void shouldRollbackToPreviousVersion() {
        SkillCatalog catalog = new SkillCatalog();
        StubToolRegistry registry = new StubToolRegistry();
        SynthesizedSkillRegistrar registrar = new SynthesizedSkillRegistrar();

        registrar.register(spec("research", "v1"), catalog, registry);
        registrar.register(spec("research", "v2"), catalog, registry);
        assertEquals("v2", catalog.find("skill:research").get().getDescription());

        boolean rolled = registrar.rollback("skill:research", catalog, registry);

        assertTrue(rolled);
        assertEquals("v1", catalog.find("skill:research").get().getDescription(), "回滚恢复上一版本");
        assertEquals(1, registrar.versionCount("skill:research"));
    }

    @Test
    void shouldUnregisterWhenRollbackToEmpty() {
        SkillCatalog catalog = new SkillCatalog();
        StubToolRegistry registry = new StubToolRegistry();
        SynthesizedSkillRegistrar registrar = new SynthesizedSkillRegistrar();

        registrar.register(spec("research", "v1"), catalog, registry);
        boolean rolled = registrar.rollback("skill:research", catalog, registry);

        assertTrue(rolled);
        assertTrue(catalog.find("skill:research").isEmpty());
        assertTrue(registry.find("skill:research").isEmpty());
        assertEquals(0, registrar.versionCount("skill:research"));
    }

    @Test
    void shouldReturnFalseWhenNoHistoryToRollback() {
        SkillCatalog catalog = new SkillCatalog();
        StubToolRegistry registry = new StubToolRegistry();
        SynthesizedSkillRegistrar registrar = new SynthesizedSkillRegistrar();

        assertFalse(registrar.rollback("skill:absent", catalog, registry));
        assertFalse(registrar.rollback("", catalog, registry));
    }
}
