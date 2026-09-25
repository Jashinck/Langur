package org.skylark.langur.infrastructure.harness.tool.skill;

/**
 * 技能步骤类型（§7.5 / H8）- 承载自合成复杂技能所需的完整编排原语。
 */
public enum StepType {

    /** 调用一个已注册工具（经 ToolDispatcher 四层校验链）。 */
    TOOL_CALL,

    /** 条件分支：按表达式结果跳转到指定步骤或结束。 */
    CONDITION,

    /**
     * 语义决策分支（J10⑨）：由 {@code DecisionPort} 求值的语义条件（noul/score），
     * 达阈值跳 {@code onTrue} 否则 {@code onFalse}。与 {@link #CONDITION}（确定性表达式）并存，各展所长；
     * 决策平面缺失时降级为 CONDITION（若可表达）或默认放行分支（P10）。沿用步数硬上限防环。
     */
    DECISION,

    /** 调用大模型（M4 ACTION / M5 REASONING），产出文本写入上下文。 */
    LLM_CALL,

    /** 循环：带退出条件 + 次数上限，循环体为嵌套步骤列表。 */
    LOOP,

    /** 并行：并发执行多个子步骤分支后汇聚结果。 */
    PARALLEL,

    /** 子流程：嵌套调用另一个已注册技能（经 ToolDispatcher，保留四层校验链）。 */
    SUB_WORKFLOW,

    /** 子 Agent：委派子 Agent 执行（为 H12 多智能体铺路）。 */
    SUB_AGENT
}
