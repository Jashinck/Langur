package org.skylark.langur.infrastructure.harness.tool.mcp;

import java.util.Map;

/**
 * MCP 服务端 {@code tools/list} 返回的单个工具描述。
 */
public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
}
