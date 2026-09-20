package org.skylark.langur.application.stream;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 长任务进度总线（§13.3 / T8）：按 taskId 维护监听器注册表。
 * <p>遵循"先注册后执行"，配合 SSE 早期事件缓冲，避免丢失 accepted/progress 等早期进度事件。</p>
 */
@Component
public class TaskProgressBus {

    private final ConcurrentMap<String, List<StreamEventHandler>> listeners = new ConcurrentHashMap<>();

    public void register(String taskId, StreamEventHandler handler) {
        listeners.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void publish(String taskId, String event, String data) {
        List<StreamEventHandler> handlers = listeners.get(taskId);
        if (handlers != null) {
            handlers.forEach(handler -> handler.send(event, data));
        }
    }

    public void complete(String taskId) {
        List<StreamEventHandler> handlers = listeners.remove(taskId);
        if (handlers != null) {
            handlers.forEach(StreamEventHandler::complete);
        }
    }

    public void fail(String taskId, Throwable error) {
        List<StreamEventHandler> handlers = listeners.remove(taskId);
        if (handlers != null) {
            handlers.forEach(handler -> handler.fail(error));
        }
    }
}
