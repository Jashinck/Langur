package org.skylark.langur.infrastructure.harness.tool.skill;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 技能规格（§7.5）- 由 {@link SkillRegistrar} 从 {@link SkillDef} Bean 构建的注册单元。
 * <p>工具 ID 规范：{@code skill:{name}}。</p>
 */
@Getter
@Builder
public class SkillSpec {

    private final String name;
    private final String description;
    private final String permission;
    private final String riskLevel;
    private final Map<String, Object> inputSchema;
    private final List<SkillStep> steps;
    @Builder.Default
    private final Duration timeout = Duration.ofSeconds(60);

    public String toolId() {
        return "skill:" + name;
    }
}
