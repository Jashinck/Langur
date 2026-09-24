package org.skylark.langur.infrastructure.harness.tool.mcp;

import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

/**
 * MCP 传输层抽象 - 屏蔽底层（HTTP/SSE、stdio）差异，仅暴露一次 JSON-RPC 请求-响应。
 * <p>抽象成接口以便单测以桩替换，无需真实网络。</p>
 */
public interface McpTransport {

    /**
     * 发送一个 JSON-RPC 请求并返回响应；notification（id 为 null）可返回 {@code null}。
     */
    JsonRpcResponse send(JsonRpcRequest request) throws Exception;
}
