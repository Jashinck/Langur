package org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc;

/**
 * JSON-RPC 2.0 请求体（MCP 传输层）。id 为 null 时表示 notification（无响应）。
 */
public record JsonRpcRequest(String jsonrpc, String id, String method, Object params) {

    public static JsonRpcRequest of(String id, String method, Object params) {
        return new JsonRpcRequest("2.0", id, method, params);
    }

    public static JsonRpcRequest notification(String method, Object params) {
        return new JsonRpcRequest("2.0", null, method, params);
    }
}
