package org.skylark.langur.infrastructure.harness.rsi;

import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillCatalog;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillSpec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合成技能注册器（R3）——<b>版本化 + 一键回滚</b>的自合成技能落库通道。
 * <p>注册：候选 {@link SkillSpec} 推入按 {@code skill:{name}} 键控的版本栈（最新在顶），写 {@link SkillCatalog}
 * （编排目录）与 {@link ToolRegistry}（四层校验元数据，source=SKILL），与 {@code @SkillDef} 手工技能同受管控。
 * 回滚：弹出当前版本，恢复栈顶上一版本；栈空则注销（目录移除 + 注册中心注销）。纯 JDK 零 Spring 依赖，
 * 由 start 装配（R3 配置驱动，默认关）。</p>
 */
public class SynthesizedSkillRegistrar {

    private final Map<String, Deque<SkillSpec>> versions = new ConcurrentHashMap<>();

    /** 注册一个合成技能（版本 +1，置为当前生效版本）。 */
    public void register(SkillSpec spec, SkillCatalog catalog, ToolRegistry registry) {
        if (spec == null) {
            return;
        }
        versions.computeIfAbsent(spec.toolId(), k -> new ArrayDeque<>()).push(spec);
        catalog.register(spec);
        registry.register(toDefinition(spec));
    }

    /**
     * 回滚到上一版本；当前为唯一版本时注销该技能。
     *
     * @return 是否发生了回滚（false = 无历史可回滚）
     */
    public boolean rollback(String toolId, SkillCatalog catalog, ToolRegistry registry) {
        if (toolId == null || toolId.isBlank()) {
            return false;
        }
        Deque<SkillSpec> stack = versions.get(toolId);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        stack.pop();
        if (stack.isEmpty()) {
            catalog.unregister(toolId);
            registry.unregister(toolId);
        } else {
            SkillSpec previous = stack.peek();
            catalog.register(previous);
            registry.register(toDefinition(previous));
        }
        return true;
    }

    /** 某技能的当前版本数（观测/审计）。 */
    public int versionCount(String toolId) {
        Deque<SkillSpec> stack = versions.get(toolId);
        return stack == null ? 0 : stack.size();
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
