package org.skylark.langur.infrastructure.harness.tool.skill;

import java.util.Map;

/**
 * 子 Agent 委派接缝（H8）- 供 {@code SUB_AGENT} 步骤委派子 Agent 执行指令（为 H12 铺路）。
 * <p>可选依赖：未配置时 {@code SUB_AGENT} 步骤抛出明确的 {@link SkillExecutionException}。</p>
 */
@FunctionalInterface
public interface SubAgentInvoker {

    /** 委派 {@code agentId} 执行 {@code instruction}，携带上下文变量，返回子 Agent 产出文本。 */
    String run(String agentId, String instruction, Map<String, Object> context);
}
