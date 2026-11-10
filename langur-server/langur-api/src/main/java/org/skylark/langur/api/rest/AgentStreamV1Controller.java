package org.skylark.langur.api.rest;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.skylark.langur.api.dto.ChatRequest;
import org.skylark.langur.api.dto.MessagePartRequest;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.stream.AgentStreamApplicationService;
import org.skylark.langur.application.stream.StreamEventHandler;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1 流式接入体系（对齐架构设计 §13.1，T8）：SSE 双入口。
 * <ul>
 *   <li>{@code POST /api/v1/agent/stream/chat}：message → summary → done</li>
 *   <li>{@code POST /api/v1/agent/stream/task}：accepted → progress → summary → done</li>
 * </ul>
 * <p>采用 Spring MVC {@link SseEmitter}（D4 决策）；异步执行避免阻塞容器线程，
 * SseEmitter 对初始化前的早期事件内置缓冲重放，保证"先注册后执行"不丢事件。</p>
 */
@RestController
@RequestMapping("/api/v1/agent/stream")
public class AgentStreamV1Controller {

    private final AgentStreamApplicationService streamApplicationService;
    private final ExecutorService emitterExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "sse-emitter");
        thread.setDaemon(true);
        return thread;
    });

    public AgentStreamV1Controller(AgentStreamApplicationService streamApplicationService) {
        this.streamApplicationService = streamApplicationService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        StreamEventHandler handler = new SseStreamEventHandler(emitter);
        RunAgentCommand command = toCommand(request);
        emitterExecutor.execute(() -> streamApplicationService.streamChat(command, handler));
        return emitter;
    }

    @PostMapping(value = "/task", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        StreamEventHandler handler = new SseStreamEventHandler(emitter);
        RunAgentCommand command = toCommand(request);
        emitterExecutor.execute(() -> streamApplicationService.streamTask(command, handler));
        return emitter;
    }

    private RunAgentCommand toCommand(ChatRequest request) {
        return RunAgentCommand.builder()
                .agentId(request.getAgentId())
                .userMessage(request.getUserMessage())
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .sessionId(request.getSessionId())
                .messageParts(toMessagePartInputs(request.getMessageParts()))
                .build();
    }

    private List<MessagePartInput> toMessagePartInputs(List<MessagePartRequest> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        return parts.stream()
                .map(part -> MessagePartInput.builder()
                        .type(part.getType())
                        .content(part.getContent())
                        .mediaUrl(part.getMediaUrl())
                        .build())
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        emitterExecutor.shutdownNow();
    }

    /** SSE 事件回调适配：将应用层流式事件写出到 {@link SseEmitter}。 */
    private static class SseStreamEventHandler implements StreamEventHandler {

        private final SseEmitter emitter;

        SseStreamEventHandler(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String event, String data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                // 客户端已断开或响应已提交，静默忽略
            }
        }

        @Override
        public void complete() {
            emitter.complete();
        }

        @Override
        public void fail(Throwable error) {
            emitter.completeWithError(error);
        }
    }
}
