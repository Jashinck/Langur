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
}
