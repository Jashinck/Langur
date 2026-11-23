package org.skylark.langur.domain.harness.multiagent;

import java.util.List;

/**
 * Agent 消息总线端口（H12）。跨 Agent 通信的领域抽象——<b>AgentId 隔离</b>：每个 Agent 拥有独立收件箱，
 * {@link #poll(String)} 仅取<b>自身</b>收件箱消息，收不到发给其它 Agent 的消息（隔离红线）。
 * <p>domain 端口（P3 依赖倒置）；infra 提供内存/消息中间件实现（H12 缺省内存，离线确定性、零外部依赖）。
 * 委派/汇聚/仲裁语义（v3.0 决策平面 choice 选子 Agent、score 仲裁汇聚结果）由消费方叠加。</p>
 */
public interface AgentMessageBus {

    /** 投递一条消息到 {@code toAgentId} 的收件箱。 */
    void publish(AgentMessage message);

    /** 拉取并清空 {@code agentId} 自身收件箱的消息（AgentId 隔离）。 */
    List<AgentMessage> poll(String agentId);
}
