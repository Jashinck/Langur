package org.skylark.langur.infrastructure.harness.tool;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.ToolProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * T 组件 - 本地工具九元组注册桥接。
 * <p>启动时将既有 {@link ToolProvider} 提供的可执行工具，按九元组标准注册进统一注册中心，
 * 使本地工具与 MCP/REST_API/SKILL 工具在同一套校验链下被无差别管控。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalToolRegistrar {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ToolRegistry toolRegistry;
    private final List<ToolProvider> toolProviders;

    @PostConstruct
    public void registerLocalTools() {
        toolProviders.stream()
                .flatMap(provider -> provider.getTools().stream())
                .forEach(this::registerIfAbsent);
    }

    private void registerIfAbsent(Tool tool) {
        if (toolRegistry.find(tool.getName()).isPresent()) {
            return;
        }
        ToolDefinitionEntity entity = ToolDefinitionEntity.builder()
                .toolId(tool.getName())
                .description(tool.getDescription())
                .inputSchema(tool.getDefinition().getParametersSchema())
                .outputSchema(null)
                .permission(null)
                .riskLevel(RiskLevel.LOW)
                .whitelist(List.of())
                .rateLimitPerMinute(null)
                .timeout(DEFAULT_TIMEOUT)
                .source(ToolSource.LOCAL)
                .build();
        toolRegistry.register(entity);
        log.info("[T] registered local tool into unified registry: {}", tool.getName());
    }
}
