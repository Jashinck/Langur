package org.skylark.langur.domain.harness.tool;

/**
 * 工具来源 - 五类统一接入
 */
public enum ToolSource {
    /** 框架内置原子工具 */
    LOCAL,
    /** 业务方通过 SPI 注入的工具 */
    SPI,
    /** MCP 协议接入的外部工具服务器 */
    MCP,
    /** 存量 HTTP API 零改造工具化 */
    REST_API,
    /** 多工具/子流程/子 Agent 组合编排 */
    SKILL
}
