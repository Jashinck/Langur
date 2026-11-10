package org.skylark.langur.infrastructure.harness.tool.skill;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.InMemoryToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10c 验收：{@link SkillRegistrar} 扫描 {@link SkillDef} Bean，以 {@code skill:{name}}（source=SKILL）
 * 注册进目录与统一注册中心，风险等级/超时按注解落地；无技能 Bean 时零副作用。
 */
class SkillRegistrarTest {

    @SkillDef(name = "order-lookup", description = "Look up an order", riskLevel = "MEDIUM", timeoutSeconds = 15)
    static class OrderLookupSkill implements Skill {
        @Override
        public List<SkillStep> steps() {
            return List.of(SkillStep.toolCall("s1", "tool:echo", Map.of("v", "${input.id}")));
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "required", List.of("id"));
        }
    }

    @Test
    void shouldRegisterAnnotatedSkillIntoRegistryAndCatalog() {
        SkillCatalog catalog = new SkillCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        SkillRegistrar registrar = new SkillRegistrar(List.of(new OrderLookupSkill()), catalog, registry);

        registrar.register();

        assertTrue(catalog.find("skill:order-lookup").isPresent());
        Optional<ToolDefinitionEntity> def = registry.find("skill:order-lookup");
        assertTrue(def.isPresent());
        assertEquals(ToolSource.SKILL, def.get().getSource());
        assertEquals(RiskLevel.MEDIUM, def.get().getRiskLevel());
        assertEquals(15, def.get().getTimeout().toSeconds());
        assertEquals(1, catalog.find("skill:order-lookup").get().getSteps().size());
    }

    @Test
    void shouldStayIdleWhenNoSkills() {
        SkillCatalog catalog = new SkillCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        new SkillRegistrar(List.of(), catalog, registry).register();

        assertTrue(catalog.all().isEmpty());
    }
}
