package org.skylark.langur.infrastructure.harness.tool.mcp;

import java.util.Map;

/**
 * MCP 工具网关（§7.3 MCP 路由点）- 由 T 组件调度器在四层校验通过后调用。
 * <p>实现负责 MCP 客户端的连接管理、工具发现与 {@code tools/call} 执行。</p>
 */
public interface McpToolGateway {

    /** 该网关是否支持指定工具（存在对应规格且已发现）。 */
    boolean supports(String toolId);

    /** 执行 MCP 工具调用，返回文本内容。 */
    String execute(String toolId, Map<String, Object> arguments) throws Exception;
}
