package org.skylark.langur.infrastructure.harness.multiagent;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.multiagent.AgentMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H12 Agent 消息总线内存实现测试（无 Mockito）。
 * <p>覆盖：投递/拉取、AgentId 隔离（B 的消息 A 取不到）、拉取即清空、空/空 id 守卫。</p>
 */
class InMemoryAgentMessageBusTest {

    @Test
    void shouldDeliverAndPollMessage() {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        bus.publish(AgentMessage.of("A", "B", "t1", "do work", 1000L));

        java.util.List<AgentMessage> first = bus.poll("B");
        assertEquals(1, first.size());
        assertEquals("t1", first.get(0).taskId());
        assertTrue(bus.poll("B").isEmpty(), "拉取即清空");
    }

    @Test
    void shouldIsolateInboxesByAgentId() {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        bus.publish(AgentMessage.of("A", "B", "t1", "for B", 1000L));

        assertTrue(bus.poll("A").isEmpty(), "A 的收件箱不应看到发给 B 的消息（AgentId 隔离）");
        assertEquals(1, bus.poll("B").size());
    }

    @Test
    void shouldDrainOnPoll() {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        bus.publish(AgentMessage.of("A", "B", "t1", "x", 1L));
        bus.publish(AgentMessage.of("A", "B", "t2", "y", 2L));

        assertEquals(2, bus.poll("B").size());
        assertTrue(bus.poll("B").isEmpty(), "拉取即清空");
    }

    @Test
    void shouldIgnoreNullOrBlankAgent() {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        assertTrue(bus.poll(null).isEmpty());
        assertTrue(bus.poll("  ").isEmpty());
        bus.publish(null); // 不抛异常
    }
}
