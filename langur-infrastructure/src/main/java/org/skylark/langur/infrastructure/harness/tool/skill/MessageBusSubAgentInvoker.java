package org.skylark.langur.infrastructure.harness.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.multiagent.AgentDispatcher;
import org.skylark.langur.domain.harness.multiagent.AgentMessage;
import org.skylark.langur.domain.harness.multiagent.AgentMessageBus;

import java.util.Map;
import java.util.UUID;

/**
 * 消息总线子 Agent 委派器（H12 执行接缝）——把 H8 的 {@link SubAgentInvoker} 接缝桥接到 H12 的
 * {@link AgentDispatcher}/{@link AgentMessageBus}：SUB_AGENT 步骤经此把指令投递到子 Agent 收件箱，
 * 并在有界超时内轮询自身收件箱等待汇聚结果。
 * <p>同步语义（{@code run} 返回子 Agent 产出文本）：以唯一 taskId 关联委派与结果，子 Agent 侧经
 * {@link AgentDispatcher#respond} 回发。超时/中断抛 {@link SkillExecutionException}（P10 不静默）。
 * 缺省超时 30s、轮询间隔 50ms（可构造注入）。纯 JDK + Lombok，无 Spring 依赖，可离线单测。</p>
 */
@Slf4j
public class MessageBusSubAgentInvoker implements SubAgentInvoker {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 50L;

    private final AgentDispatcher dispatcher;
    private final AgentMessageBus bus;
    private final String requesterAgentId;
    private final long timeoutMillis;
    private final long pollIntervalMillis;

    public MessageBusSubAgentInvoker(AgentDispatcher dispatcher, AgentMessageBus bus, String requesterAgentId) {
        this(dispatcher, bus, requesterAgentId, DEFAULT_TIMEOUT_MILLIS, DEFAULT_POLL_INTERVAL_MILLIS);
    }

    public MessageBusSubAgentInvoker(AgentDispatcher dispatcher, AgentMessageBus bus,
                                     String requesterAgentId, long timeoutMillis, long pollIntervalMillis) {
        if (dispatcher == null || bus == null) {
            throw new IllegalArgumentException("AgentDispatcher and AgentMessageBus must not be null");
        }
        if (requesterAgentId == null || requesterAgentId.isBlank()) {
            throw new IllegalArgumentException("requesterAgentId must not be blank");
        }
        this.dispatcher = dispatcher;
        this.bus = bus;
        this.requesterAgentId = requesterAgentId;
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        this.pollIntervalMillis = pollIntervalMillis > 0 ? pollIntervalMillis : DEFAULT_POLL_INTERVAL_MILLIS;
    }

    @Override
    public String run(String agentId, String instruction, Map<String, Object> context) {
        String taskId = "sub-" + UUID.randomUUID();
        dispatcher.dispatch(requesterAgentId, agentId, taskId, instruction, bus);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (AgentMessage message : bus.poll(requesterAgentId)) {
                if (taskId.equals(message.taskId())) {
                    return message.payload();
                }
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SkillExecutionException("SUB_AGENT [" + agentId + "] interrupted awaiting result", e);
            }
        }
        throw new SkillExecutionException("SUB_AGENT [" + agentId + "] timed out awaiting result");
    }
}
