package org.skylark.langur.domain.harness.context;

import java.util.Optional;

/**
 * C 组件 - 上下文仓储（端口），基础设施层提供向量库/缓存实现
 */
public interface AgentContextRepository {

    void save(AgentContext context);

    Optional<AgentContext> findById(String contextId);
}
