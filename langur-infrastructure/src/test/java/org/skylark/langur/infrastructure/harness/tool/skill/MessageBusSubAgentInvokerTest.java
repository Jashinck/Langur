package org.skylark.langur.infrastructure.harness.tool.skill;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.multiagent.AgentDispatcher;
import org.skylark.langur.domain.harness.multiagent.AgentMessage;
import org.skylark.langur.infrastructure.harness.multiagent.InMemoryAgentMessageBus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H12 SUB_AGENT 执行接缝测试（无 Mockito，真实 {@link AgentDispatcher} + {@link InMemoryAgentMessageBus}）。
 * <p>覆盖：委派→子 Agent 回发→汇聚结果、无响应超时抛错且委派已投递、空依赖守卫。</p>
 */
class MessageBusSubAgentInvokerTest {

    @Test
    void shouldDelegateAndReturnAggregatedResult() throws Exception {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        AgentDispatcher dispatcher = new AgentDispatcher();
        MessageBusSubAgentInvoker invoker = new MessageBusSubAgentInvoker(dispatcher, bus, "main", 5_000L, 5L);

        // 子 Agent 侧：轮询自身收件箱，收到委派后经 respond 回发结果
        Thread worker = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline) {
                for (AgentMessage message : bus.poll("worker-1")) {
                    dispatcher.respond("worker-1", message.fromAgentId(), message.taskId(),
                            "done:" + message.payload(), bus);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        worker.setDaemon(true);
        worker.start();

        String result = invoker.run("worker-1", "转译PRD", Map.of());

        assertEquals("done:转译PRD", result);
    }

    @Test
    void shouldTimeoutWhenSubAgentDoesNotRespond() {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        AgentDispatcher dispatcher = new AgentDispatcher();
        MessageBusSubAgentInvoker invoker = new MessageBusSubAgentInvoker(dispatcher, bus, "main", 100L, 10L);

        assertThrows(SkillExecutionException.class,
                () -> invoker.run("worker-1", "x", Map.of()));
        // 委派已投递到子 Agent 收件箱（超时是汇聚侧失败，非投递失败）
        assertEquals(1, bus.poll("worker-1").size());
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new MessageBusSubAgentInvoker(null, new InMemoryAgentMessageBus(), "main"));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageBusSubAgentInvoker(new AgentDispatcher(), null, "main"));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageBusSubAgentInvoker(new AgentDispatcher(), new InMemoryAgentMessageBus(), " "));
    }

    @Test
    void shouldRespondToRequesterInbox() {
        InMemoryAgentMessageBus bus = new InMemoryAgentMessageBus();
        AgentDispatcher dispatcher = new AgentDispatcher();

        dispatcher.respond("worker-1", "main", "t1", "result-text", bus);

        java.util.List<AgentMessage> inbox = bus.poll("main");
        assertEquals(1, inbox.size());
        assertEquals("t1", inbox.get(0).taskId());
        assertEquals("result-text", inbox.get(0).payload());
    }
}
