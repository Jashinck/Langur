package org.skylark.langur.infrastructure.harness.multiagent;

import org.skylark.langur.domain.harness.multiagent.AgentMessage;
import org.skylark.langur.domain.harness.multiagent.AgentMessageBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent 消息总线内存实现（H12，缺省装配）。按 {@code toAgentId} 键控的独立收件箱队列，
 * {@code ConcurrentHashMap<String, ConcurrentLinkedQueue>} 线程安全、AgentId 隔离、离线确定性、
 * 零外部依赖；可被消息中间件（Spring Event / RocketMQ）实现覆盖（P5 开闭）。</p>
 */
public class InMemoryAgentMessageBus implements AgentMessageBus {

    private final ConcurrentMap<String, ConcurrentLinkedQueue<AgentMessage>> inboxes = new ConcurrentHashMap<>();

    @Override
    public void publish(AgentMessage message) {
        if (message == null) {
            return;
        }
        inboxes.computeIfAbsent(message.toAgentId(), k -> new ConcurrentLinkedQueue<>()).add(message);
    }

    @Override
    public List<AgentMessage> poll(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        ConcurrentLinkedQueue<AgentMessage> queue = inboxes.get(agentId);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> drained = new ArrayList<>();
        AgentMessage message;
        while ((message = queue.poll()) != null) {
            drained.add(message);
        }
        return drained;
    }
}
