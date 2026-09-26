package org.skylark.langur.domain.harness.multiagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H12 Agent 任务分发器离线确定性测试（无 Mockito，匿名桩消息总线）。
 * <p>覆盖：跨 Agent 委派、目标 Agent 收件、自委派隔离、空目标/空总线守卫。</p>
 */
class AgentDispatcherTest {

    private static final class StubBus implements AgentMessageBus {
        final ConcurrentMap<String, ConcurrentLinkedQueue<AgentMessage>> inboxes = new ConcurrentHashMap<>();

        @Override
        public void publish(AgentMessage message) {
            inboxes.computeIfAbsent(message.toAgentId(), k -> new ConcurrentLinkedQueue<>()).add(message);
        }

        @Override
        public List<AgentMessage> poll(String agentId) {
            ConcurrentLinkedQueue<AgentMessage> q = inboxes.get(agentId);
            if (q == null || q.isEmpty()) {
                return List.of();
            }
            java.util.ArrayList<AgentMessage> drained = new java.util.ArrayList<>();
            AgentMessage m;
            while ((m = q.poll()) != null) {
                drained.add(m);
            }
            return drained;
        }
    }

    private final AgentDispatcher dispatcher = new AgentDispatcher();

    @Test
    void shouldDispatchTaskToTargetAgent() {
        StubBus bus = new StubBus();
        dispatcher.dispatch("main", "worker-1", "t1", "转译 PRD", bus);

        List<AgentMessage> inbox = dispatcher.pollTasks("worker-1", bus);
        assertEquals(1, inbox.size());
        assertEquals("main", inbox.get(0).fromAgentId());
        assertEquals("t1", inbox.get(0).taskId());
        assertEquals("转译 PRD", inbox.get(0).payload());
    }

    @Test
    void shouldNotDeliverToOtherAgentsInbox() {
        StubBus bus = new StubBus();
        dispatcher.dispatch("main", "worker-1", "t1", "x", bus);

        assertTrue(dispatcher.pollTasks("worker-2", bus).isEmpty(), "委派只进目标收件箱");
    }

    @Test
    void shouldRejectSelfDispatch() {
        StubBus bus = new StubBus();
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch("main", "main", "t1", "x", bus));
    }

    @Test
    void shouldRejectBlankTargetOrNullBus() {
        StubBus bus = new StubBus();
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch("main", " ", "t1", "x", bus));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch("main", "worker", "t1", "x", null));
    }
}
