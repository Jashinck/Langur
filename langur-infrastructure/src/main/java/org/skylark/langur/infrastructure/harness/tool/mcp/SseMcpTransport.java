package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

import java.net.URI;
import java.util.Map;

/**
 * {@link McpTransport} 的真流式 SSE 实现（H6，§7.3）。
 * <p>入站：持久订阅 {@code text/event-stream} 事件流，逐帧交 {@link #deliver} 关联响应/派发通知
 * （含 {@code tools/list_changed}）；出站：每条请求经 POST 发往消息端点。区别于
 * {@link HttpMcpTransport} 的单次请求-响应，此实现维持长连接以接收服务端主动推送。</p>
 */
@Slf4j
public class SseMcpTransport extends AbstractCorrelatingTransport {

    private final McpSender sender;
    private volatile Disposable subscription;

    /** 出站接缝构造（单测可注入录制型 sender，并直接调用 {@link #deliver} 模拟入站事件）。 */
    public SseMcpTransport(McpSender sender, ObjectMapper mapper, long timeoutMillis) {
        super(mapper, timeoutMillis);
        this.sender = sender;
    }

    /**
     * 建立真实 SSE 传输：订阅入站事件流并以下述 POST 端点作出站。
     *
     * @param sseUri  入站事件流端点（GET, text/event-stream）
     * @param postUri 出站消息端点（POST, application/json）
     */
    public static SseMcpTransport connect(WebClient webClient, URI sseUri, URI postUri,
                                          Map<String, String> headers, ObjectMapper mapper, long timeoutMillis) {
        SseMcpTransport transport = new SseMcpTransport(json -> webClient.post()
                .uri(postUri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (headers != null) {
                        headers.forEach(h::add);
                    }
                })
                .bodyValue(json)
                .retrieve()
                .toBodilessEntity()
                .block(), mapper, timeoutMillis);
        transport.subscription = webClient.get()
                .uri(sseUri)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> {
                    if (headers != null) {
                        headers.forEach(h::add);
                    }
                })
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(transport::deliver,
                        error -> log.warn("[T] MCP SSE stream error on {}: {}", sseUri, error.getMessage()));
        return transport;
    }

    @Override
    protected void doSend(String json) throws Exception {
        sender.send(json);
    }

    @Override
    public void close() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
    }
}
