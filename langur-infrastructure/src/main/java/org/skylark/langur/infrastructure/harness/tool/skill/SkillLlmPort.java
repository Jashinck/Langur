package org.skylark.langur.infrastructure.harness.tool.skill;

/**
 * 技能内 LLM 调用接缝（H8）- 供 {@code LLM_CALL} 步骤调用模型，与 {@code LlmGateway} 解耦以便纯单测。
 * <p>{@code modelRole} 对应 {@code ModelRole} 名称（ACTION=M4 / REASONING=M5），由适配层翻译。</p>
 */
@FunctionalInterface
public interface SkillLlmPort {

    /** 以指定模型角色补全一段提示词，返回文本产出。 */
    String complete(String modelRole, String systemPrompt, String userPrompt);
}
