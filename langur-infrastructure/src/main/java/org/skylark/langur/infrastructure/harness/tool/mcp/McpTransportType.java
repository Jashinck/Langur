package org.skylark.langur.infrastructure.harness.tool.mcp;

/**
 * MCP 传输类型（H6，§7.3）- 决定 {@link McpClientManager} 为服务端装配哪种 {@link McpTransport}。
 */
public enum McpTransportType {

    /** JSON-RPC over HTTP(S)，单请求-响应（可兼容 SSE 分帧响应体）。 */
    HTTP,

    /** 进程管道：拉起本地子进程，按行读写 stdin/stdout 的 JSON-RPC。 */
    STDIO,

    /** 真流式 SSE：持久入站事件流接收响应/通知，出站经 POST 发送请求。 */
    SSE,

    /** WebSocket：全双工帧，按 id 关联响应，服务端通知实时下发。 */
    WS
}
