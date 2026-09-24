package org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc;

/**
 * JSON-RPC 2.0 错误对象。
 */
public record JsonRpcError(int code, String message, Object data) {
}
