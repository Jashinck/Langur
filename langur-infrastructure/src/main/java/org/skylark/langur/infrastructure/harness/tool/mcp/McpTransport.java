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

    /**
     * 装配服务端通知监听器（H6：订阅 {@code tools/list_changed} 热更新）。
     * <p>仅双向/流式传输（STDIO/SSE/WS）需要；纯请求-响应传输（HTTP）默认无操作。</p>
     */
    default void setNotificationListener(McpNotificationListener listener) {
    }

    /** 释放底层资源（进程/连接/订阅）；默认无操作。 */
    default void close() {
    }
}
