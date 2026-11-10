package org.skylark.langur.infrastructure.harness.tool.mcp;

/**
 * MCP 交互异常（连接、握手、JSON-RPC 错误响应等）。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
