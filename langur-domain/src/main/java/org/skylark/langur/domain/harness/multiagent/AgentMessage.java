package org.skylark.langur.domain.harness.multiagent;

/**
 * Agent 间消息（H12）。跨 Agent 通信的统一信封——承载发送/接收方、任务关联 id、载荷与时间戳。
 * <p>纯 JDK record，零外部依赖（P1）；构造时收敛缺省、blank 收发方/任务 id 拒绝（隔离红线）。</p>
 *
 * @param fromAgentId 发送方 Agent id
 * @param toAgentId   接收方 Agent id（路由到其独立收件箱）
 * @param taskId      任务关联 id（委派/汇聚结果对齐）
 * @param payload     载荷（委派指令或汇聚结果文本）
 * @param timestamp   时间戳（毫秒）
 */
public record AgentMessage(String fromAgentId,
                           String toAgentId,
                           String taskId,
                           String payload,
                           long timestamp) {

    public AgentMessage {
        if (fromAgentId == null || fromAgentId.isBlank()) {
            throw new IllegalArgumentException("AgentMessage fromAgentId must not be blank");
        }
        if (toAgentId == null || toAgentId.isBlank()) {
            throw new IllegalArgumentException("AgentMessage toAgentId must not be blank");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("AgentMessage taskId must not be blank");
        }
        payload = (payload == null) ? "" : payload;
        timestamp = Math.max(0L, timestamp);
    }

    public static AgentMessage of(String fromAgentId, String toAgentId, String taskId,
                                  String payload, long timestamp) {
        return new AgentMessage(fromAgentId, toAgentId, taskId, payload, timestamp);
    }
}
