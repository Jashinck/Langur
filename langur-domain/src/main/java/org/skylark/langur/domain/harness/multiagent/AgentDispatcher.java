package org.skylark.langur.domain.harness.multiagent;

import java.util.List;

/**
 * Agent 任务分发器（H12）——跨 Agent 任务委派的领域原语，经 {@link AgentMessageBus} 传递。
 * <p>① <b>委派</b> {@link #dispatch}：把任务（指令 + 关联 id）投递到目标 Agent 收件箱，禁止自委派
 * （隔离红线）；② <b>接收</b> {@link #pollTasks}：Agent 拉取<b>自身</b>收件箱的委派任务。汇聚/仲裁
 * （v3.0 决策平面 {@code choice} 选子 Agent、{@code score} 仲裁汇聚结果）为消费方叠加，本类是确定性底座。
 * 纯领域实现（无 Spring 依赖），由 start 层装配。SUB_AGENT 步骤（H8 铺路的 {@code SkillStep.subAgent}）
 * 运行期执行接缝为后续增强。</p>
 */
public class AgentDispatcher {

    /**
     * 委派任务到目标 Agent。
     *
     * @param requesterAgentId 请求方 Agent id
     * @param targetAgentId    目标子 Agent id（不可为空、不可为自身）
     * @param taskId           任务关联 id
     * @param instruction      委派指令
     * @param bus              消息总线
     * @throws IllegalArgumentException 目标为空或等于请求方（自委派隔离）
     */
    public void dispatch(String requesterAgentId, String targetAgentId, String taskId,
                         String instruction, AgentMessageBus bus) {
        if (targetAgentId == null || targetAgentId.isBlank()) {
            throw new IllegalArgumentException("targetAgentId must not be blank");
        }
        if (targetAgentId.equals(requesterAgentId)) {
            throw new IllegalArgumentException("self-dispatch is not allowed (AgentId isolation)");
        }
        if (bus == null) {
            throw new IllegalArgumentException("AgentMessageBus must not be null");
        }
        bus.publish(AgentMessage.of(requesterAgentId, targetAgentId, taskId, instruction,
                System.currentTimeMillis()));
    }

    /** 拉取自身收件箱的委派任务（AgentId 隔离由总线保证）。 */
    public List<AgentMessage> pollTasks(String agentId, AgentMessageBus bus) {
        if (agentId == null || agentId.isBlank() || bus == null) {
            return List.of();
        }
        return bus.poll(agentId);
    }
}
