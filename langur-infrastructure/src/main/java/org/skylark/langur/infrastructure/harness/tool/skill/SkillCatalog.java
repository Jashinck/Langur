package org.skylark.langur.infrastructure.harness.tool.skill;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能目录 - 与 T 组件 {@code ToolRegistry} 并行：注册中心管四层校验元数据，目录管编排步骤。
 */
@Component
public class SkillCatalog {

    private final Map<String, SkillSpec> specs = new ConcurrentHashMap<>();

    public void register(SkillSpec spec) {
        if (spec != null) {
            specs.put(spec.toolId(), spec);
        }
    }

    public Optional<SkillSpec> find(String toolId) {
        return Optional.ofNullable(specs.get(toolId));
    }

    public Collection<SkillSpec> all() {
        return specs.values();
    }
}
