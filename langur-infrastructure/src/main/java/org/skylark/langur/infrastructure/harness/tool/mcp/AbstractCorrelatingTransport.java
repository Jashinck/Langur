package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 双向/流式 MCP 传输基类（H6）- 在异步通道上实现"请求-响应按 id 关联 + 服务端通知派发"。
 * <p>{@link #send} 注册一个按请求 id 索引的 {@link CompletableFuture}，经 {@link #doSend} 写出报文后限时等待；
 * 子类收到入站报文时调用 {@link #deliver}：含 id 且带 result/error 者唤醒对应 future，含 method 者作为
 * 服务端通知交 {@link McpNotificationListener}（如 {@code tools/list_changed} 热更新）。
 * 相关性/通知逻辑与真实网络解耦，可纯单测。</p>
 */
public abstract class AbstractCorrelatingTransport implements McpTransport {

    protected final ObjectMapper mapper;
    private final long timeoutMillis;
    private final Map<String, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private volatile McpNotificationListener listener;

    protected AbstractCorrelatingTransport(ObjectMapper mapper, long timeoutMillis) {
        this.mapper = mapper;
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 30_000L;
    }

    /** 将一条已序列化的 JSON-RPC 报文写入底层通道。 */
    protected abstract void doSend(String json) throws Exception;

    @Override
    public JsonRpcResponse send(JsonRpcRequest request) throws Exception {
        String json = mapper.writeValueAsString(request);
        if (request.id() == null) {
            doSend(json);
            return null;
        }
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(request.id(), future);
        try {
            doSend(json);
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpException("MCP request timed out after " + timeoutMillis + "ms: " + request.method(), e);
        } finally {
            pending.remove(request.id());
        }
    }

    @Override
    public void setNotificationListener(McpNotificationListener listener) {
        this.listener = listener;
    }

    /**
     * 处理一条入站 JSON-RPC 报文：响应唤醒等待中的请求，通知派发给监听器。
     * 畸形帧静默忽略（流式通道可能夹杂心跳/注释行）。
     */
    public void deliver(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(rawJson);
        } catch (Exception e) {
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode idNode = node.get("id");
        boolean isResponse = idNode != null && !idNode.isNull()
                && (node.has("result") || node.has("error"));
        if (isResponse) {
            CompletableFuture<JsonRpcResponse> future = pending.get(idNode.asText());
            if (future != null) {
                try {
                    future.complete(mapper.treeToValue(node, JsonRpcResponse.class));
                } catch (Exception ignored) {
                    future.completeExceptionally(new McpException("malformed MCP response frame"));
                }
            }
            return;
        }
        JsonNode methodNode = node.get("method");
        if (methodNode != null && !methodNode.isNull()) {
            McpNotificationListener current = this.listener;
            if (current != null) {
                Object params = node.has("params")
                        ? mapper.convertValue(node.get("params"), Object.class)
                        : Map.of();
                try {
                    current.onNotification(methodNode.asText(), params);
                } catch (RuntimeException ignored) {
                    // P10：监听器异常不影响传输通道
                }
            }
        }
    }
}
