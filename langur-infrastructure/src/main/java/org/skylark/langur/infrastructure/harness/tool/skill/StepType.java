package org.skylark.langur.infrastructure.harness.tool.skill;

/**
 * 技能步骤类型（§7.5）- 首期支持工具调用与条件分支两类，其余（并行、子 Agent、循环）后续扩展。
 */
public enum StepType {

    /** 调用一个已注册工具（经 ToolDispatcher 四层校验链）。 */
    TOOL_CALL,

    /** 条件分支：按表达式结果跳转到指定步骤或结束。 */
    CONDITION
}
