package org.skylark.langur.infrastructure.harness.tool.mcp;

/**
 * MCP 出站发送接缝（H6）- 将一条已序列化的 JSON-RPC 报文写入底层通道（WS 帧 / SSE POST 等）。
 * <p>抽出为函数式接缝，使 {@link AbstractCorrelatingTransport} 的相关性/通知逻辑可脱离真实网络单测。</p>
 */
@FunctionalInterface
public interface McpSender {

    void send(String json) throws Exception;
}
