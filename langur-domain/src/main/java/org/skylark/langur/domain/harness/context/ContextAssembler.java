package org.skylark.langur.domain.harness.context;

import org.skylark.langur.domain.model.agent.Agent;

/**
 * C 组件 - 上下文装配服务（端口）。
 * <p>职责（对齐架构设计 §8.1 上下文组装阶段）：
 * 构建双画像（UserProfile/TaskProfile）、装配分层记忆、执行脱敏过滤与 Token 预算治理。</p>
 */
public interface ContextAssembler {

    /**
     * 装配执行上下文。
     *
     * @param traceId    全链路追踪 ID
     * @param bizCode    业务域编码
     * @param agent      执行主体（提供系统提示词与会话历史）
     * @param tokenBudget Token 预算（超预算的记忆写入将被拒绝）
     */
    AgentContext assemble(String traceId, String bizCode, Agent agent, long tokenBudget);
}
