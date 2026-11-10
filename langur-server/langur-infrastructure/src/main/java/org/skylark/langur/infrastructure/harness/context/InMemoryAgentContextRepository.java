package org.skylark.langur.infrastructure.harness.context;

import org.skylark.langur.domain.harness.context.AgentContext;
import org.skylark.langur.domain.harness.context.AgentContextRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * C 组件 - 内存上下文仓储实现。
 * <p>生产环境可替换为向量库（L4 知识）+ 缓存分层实现。</p>
 */
@Component
public class InMemoryAgentContextRepository implements AgentContextRepository {

    private final ConcurrentMap<String, AgentContext> store = new ConcurrentHashMap<>();

    @Override
    public void save(AgentContext context) {
        store.put(context.getContextId(), context);
    }

    @Override
    public Optional<AgentContext> findById(String contextId) {
        return Optional.ofNullable(store.get(contextId));
    }
}
