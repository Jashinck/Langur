package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link McpTransport} 的 WebSocket 实现（H6，§7.3）- 全双工帧，按 id 关联响应，服务端通知实时下发。
 * <p>出站经 {@link WebSocket#sendText}；入站由 {@link WebSocket.Listener#onText} 累积完整帧后交
 * {@link #deliver}。基于 JDK {@code java.net.http}，无额外依赖。</p>
 */
public class WebSocketMcpTransport extends AbstractCorrelatingTransport {

    private final McpSender sender;
    private final WebSocket webSocket;

    /** 出站接缝构造（单测可注入录制型 sender，并直接调用 {@link #deliver} 模拟入站）。 */
    public WebSocketMcpTransport(McpSender sender, ObjectMapper mapper, long timeoutMillis) {
        this(sender, null, mapper, timeoutMillis);
    }

    private WebSocketMcpTransport(McpSender sender, WebSocket webSocket, ObjectMapper mapper, long timeoutMillis) {
        super(mapper, timeoutMillis);
        this.sender = sender;
        this.webSocket = webSocket;
    }

    /** 建立真实 WebSocket 连接；握手失败抛 {@link McpException}。 */
    public static WebSocketMcpTransport connect(HttpClient httpClient, URI uri, Map<String, String> headers,
                                                ObjectMapper mapper, long timeoutMillis) {
        AtomicReference<WebSocketMcpTransport> ref = new AtomicReference<>();
        StringBuilder buffer = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String frame = buffer.toString();
                    buffer.setLength(0);
                    WebSocketMcpTransport transport = ref.get();
                    if (transport != null) {
                        transport.deliver(frame);
                    }
                }
                webSocket.request(1);
                return null;
            }
        };
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            if (headers != null) {
                headers.forEach(builder::header);
            }
            WebSocket webSocket = builder.buildAsync(uri, listener).join();
            WebSocketMcpTransport transport = new WebSocketMcpTransport(
                    json -> webSocket.sendText(json, true), webSocket, mapper, timeoutMillis);
            ref.set(transport);
            return transport;
        } catch (Exception e) {
            throw new McpException("failed to open MCP WebSocket " + uri + ": " + e.getMessage(), e);
        }
    }

    @Override
    protected void doSend(String json) throws Exception {
        sender.send(json);
    }

    @Override
    public void close() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
            } catch (RuntimeException ignored) {
                // 关闭失败无需上抛
            }
        }
    }
}
