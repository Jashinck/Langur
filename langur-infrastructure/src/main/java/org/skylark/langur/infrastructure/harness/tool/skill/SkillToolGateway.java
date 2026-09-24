package org.skylark.langur.infrastructure.harness.tool.skill;

import java.util.Map;

/**
 * 技能工具网关（§7.5 SKILL 路由点）- 由 T 组件调度器在四层校验通过后调用。
 * <p>实现负责按技能规格顺序执行步骤，内部工具调用回派 {@code ToolDispatcher}。</p>
 */
public interface SkillToolGateway {

    /** 该网关是否支持指定技能（存在对应规格）。 */
    boolean supports(String toolId);

    /** 执行技能编排，返回最终输出文本。 */
    String execute(String toolId, Map<String, Object> arguments) throws Exception;
}
