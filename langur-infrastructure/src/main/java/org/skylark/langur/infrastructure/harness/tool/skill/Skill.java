package org.skylark.langur.infrastructure.harness.tool.skill;

import java.util.List;
import java.util.Map;

/**
 * 技能契约（§7.5）- 业务方实现此接口并以 {@link SkillDef} 标注为 Spring Bean，
 * 即声明一个可被 LLM 当作单一工具调用的多步编排。
 */
public interface Skill {

    /** 技能的有序步骤定义。 */
    List<SkillStep> steps();

    /** 技能入参 JSON Schema，供四层校验链 Schema 层使用；缺省无约束。 */
    default Map<String, Object> inputSchema() {
        return Map.of();
    }
}
