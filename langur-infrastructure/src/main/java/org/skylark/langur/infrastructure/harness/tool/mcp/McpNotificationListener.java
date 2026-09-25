package org.skylark.langur.infrastructure.harness.tool.mcp;

/**
 * MCP 服务端通知监听器（H6）- 接收服务端主动推送的 JSON-RPC notification（如 {@code tools/list_changed}）。
 * <p>由 {@link McpClientManager} 装配，用于订阅远端工具变更触发热更新注册。</p>
 */
@FunctionalInterface
public interface McpNotificationListener {

    /** 收到一条服务端通知；实现方应快速返回，耗时处理需自行异步化。 */
    void onNotification(String method, Object params);
}
