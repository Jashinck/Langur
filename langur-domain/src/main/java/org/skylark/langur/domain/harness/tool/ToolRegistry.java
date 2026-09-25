package org.skylark.langur.domain.harness.tool;

import java.util.List;
import java.util.Optional;

/**
 * T 组件 - 工具注册中心（端口）
 */
public interface ToolRegistry {

    void register(ToolDefinitionEntity definition);

    Optional<ToolDefinitionEntity> find(String toolId);

    /** 按调用方白名单返回可见工具集（未注册/未授权不可见） */
    List<ToolDefinitionEntity> listVisible(String caller);

    /**
     * 注销工具（H6：MCP {@code tools/list_changed} 热更新移除失效远端工具）。
     * 默认无操作，便于渐进实现；内存/持久化注册中心应覆写为真实移除。
     */
    default void unregister(String toolId) {
    }
}
