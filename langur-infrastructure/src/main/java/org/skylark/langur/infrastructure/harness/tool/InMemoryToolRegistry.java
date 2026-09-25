package org.skylark.langur.infrastructure.harness.tool;

import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * T 组件 - 内存工具注册中心实现。
 * <p>生产环境可替换为 MySQL（t_tool_definition 九元组）持久化实现。</p>
 */
@Component
public class InMemoryToolRegistry implements ToolRegistry {

    private final ConcurrentMap<String, ToolDefinitionEntity> store = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDefinitionEntity definition) {
        store.put(definition.getToolId(), definition);
    }

    @Override
    public Optional<ToolDefinitionEntity> find(String toolId) {
        return Optional.ofNullable(store.get(toolId));
    }

    @Override
    public List<ToolDefinitionEntity> listVisible(String caller) {
        return store.values().stream()
                .filter(definition -> definition.isInvokableBy(caller))
                .toList();
    }

    @Override
    public void unregister(String toolId) {
        store.remove(toolId);
    }
}
