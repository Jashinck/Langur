package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API 工具目录 - 保存 {@link RestApiToolSpec}，供网关按 toolId 检索调用细节。
 * <p>与 T 组件 {@code ToolRegistry} 并行：注册中心管四层校验元数据，目录管 HTTP 调用规格。</p>
 */
@Component
public class RestApiToolCatalog {

    private final Map<String, RestApiToolSpec> specs = new ConcurrentHashMap<>();

    public void register(RestApiToolSpec spec) {
        if (spec != null) {
            specs.put(spec.toolId(), spec);
        }
    }

    public Optional<RestApiToolSpec> find(String toolId) {
        return Optional.ofNullable(specs.get(toolId));
    }

    public Collection<RestApiToolSpec> all() {
        return specs.values();
    }
}
