package org.skylark.langur.infrastructure.harness.tool.mcp;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具目录 - 与 T 组件 {@code ToolRegistry} 并行：注册中心管四层校验元数据，目录管远端调用规格。
 */
@Component
public class McpToolCatalog {

    private final Map<String, McpToolSpec> specs = new ConcurrentHashMap<>();

    public void register(McpToolSpec spec) {
        if (spec != null) {
            specs.put(spec.toolId(), spec);
        }
    }

    public Optional<McpToolSpec> find(String toolId) {
        return Optional.ofNullable(specs.get(toolId));
    }

    public Collection<McpToolSpec> all() {
        return specs.values();
    }
}
