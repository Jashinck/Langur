package org.skylark.langur.infrastructure.harness.tool.mcp;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
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

    /** 注销单个工具规格（H6 热更新移除失效远端工具）。 */
    public void unregister(String toolId) {
        specs.remove(toolId);
    }

    /** 返回指定服务端下的全部工具规格（H6 热更新差量比对）。 */
    public List<McpToolSpec> byServer(String server) {
        return specs.values().stream()
                .filter(spec -> spec.getServer() != null && spec.getServer().equals(server))
                .toList();
    }
}
