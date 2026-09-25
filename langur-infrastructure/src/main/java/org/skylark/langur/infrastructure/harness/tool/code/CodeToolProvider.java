package org.skylark.langur.infrastructure.harness.tool.code;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码访问工具提供者（Coding Agent）- {@code langur.code-access.enabled=true} 且配置了 roots 时，
 * 暴露 {@code read_file}/{@code list_dir}/{@code grep} 三个本地工具；否则返回空列表（P10 优雅降级）。
 * <p>经既有 {@code LocalToolRegistrar} 自动注册为 LOCAL 工具，纳入 T 组件四层校验链，无需改动调度器。</p>
 */
@Slf4j
@Component
public class CodeToolProvider implements ToolProvider {

    private final CodeAccessProperties properties;

    public CodeToolProvider(CodeAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Tool> getTools() {
        List<Tool> tools = new ArrayList<>();
        CodeAccessGuard guard = new CodeAccessGuard(properties);
        if (!guard.available()) {
            log.info("[T] code-access disabled or no roots configured, code tools idle");
            return tools;
        }
        tools.add(new ReadFileTool(guard, properties.getMaxFileBytes()));
        tools.add(new ListDirTool(guard));
        tools.add(new GrepTool(guard, properties.getMaxResults(), properties.getMaxFileBytes()));
        log.info("[T] code-access enabled, exposing read_file/list_dir/grep over {} root(s)",
                guard.roots().size());
        return tools;
    }
}
