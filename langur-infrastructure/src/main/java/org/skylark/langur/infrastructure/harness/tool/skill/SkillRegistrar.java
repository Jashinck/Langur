package org.skylark.langur.infrastructure.harness.tool.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 技能注册器（§7.5 启动阶段）- 扫描所有 {@link SkillDef} 标注的 {@link Skill} Bean，
 * 构建技能规格装入 {@link SkillCatalog}，并以 source=SKILL、工具 ID {@code skill:{name}}
 * 注册进 T 组件统一注册中心，使其可被 LLM 当作单一工具调用。
 * <p>无技能 Bean 时零副作用。</p>
 */
@Slf4j
@Component
public class SkillRegistrar {

    private final List<Skill> skills;
    private final SkillCatalog catalog;
    private final ToolRegistry toolRegistry;

    public SkillRegistrar(List<Skill> skills, SkillCatalog catalog, ToolRegistry toolRegistry) {
        this.skills = skills;
        this.catalog = catalog;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void register() {
        if (skills == null || skills.isEmpty()) {
            log.info("[T] no @SkillDef skills found, SKILL tool source idle");
            return;
        }
        for (Skill skill : skills) {
            SkillDef def = AnnotationUtils.findAnnotation(skill.getClass(), SkillDef.class);
            if (def == null) {
                log.warn("[T] skill bean [{}] lacks @SkillDef, skipped", skill.getClass().getName());
                continue;
            }
            SkillSpec spec = SkillSpec.builder()
                    .name(def.name())
                    .description(def.description())
                    .permission(def.permission())
                    .riskLevel(def.riskLevel())
                    .inputSchema(skill.inputSchema())
                    .steps(skill.steps())
                    .timeout(Duration.ofSeconds(def.timeoutSeconds()))
                    .build();
            catalog.register(spec);
            toolRegistry.register(toDefinition(spec));
            log.info("[T] registered SKILL into unified registry: {} ({} step(s))",
                    spec.toolId(), spec.getSteps() == null ? 0 : spec.getSteps().size());
        }
    }

    private ToolDefinitionEntity toDefinition(SkillSpec spec) {
        return ToolDefinitionEntity.builder()
                .toolId(spec.toolId())
                .description(spec.getDescription() != null ? spec.getDescription() : "Skill " + spec.toolId())
                .inputSchema(spec.getInputSchema())
                .outputSchema(null)
                .permission(spec.getPermission())
                .riskLevel(parseRisk(spec.getRiskLevel()))
                .whitelist(List.of())
                .rateLimitPerMinute(null)
                .timeout(spec.getTimeout())
                .source(ToolSource.SKILL)
                .build();
    }

    private RiskLevel parseRisk(String risk) {
        try {
            return risk == null || risk.isBlank() ? RiskLevel.LOW : RiskLevel.valueOf(risk.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.LOW;
        }
    }
}
