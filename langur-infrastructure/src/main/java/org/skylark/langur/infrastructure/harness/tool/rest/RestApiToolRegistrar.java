package org.skylark.langur.infrastructure.harness.tool.rest;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * REST API 工具注册器（§8.1 启动阶段）- 将配置的 REST API 工具装载进凭证库、目录与 T 组件注册中心。
 * <p>注册后与本地工具无差别，统一经四层校验链管控；未启用时不产生任何副作用。
 * H7：除手动 YAML 工具（{@code tools}）外，追加 OpenAPI 文档自动发现（{@code openapi}）——
 * 逐来源加载/解析文档、生成工具规格并注册；单个来源失败仅告警降级，不阻断启动（P10）。</p>
 */
@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class RestApiToolRegistrar {

    private final RestApiToolProperties properties;
    private final RestApiToolCatalog catalog;
    private final ToolRegistry toolRegistry;
    private final InMemoryCredentialVault credentialVault;
    private final OpenApiSpecLoader openApiSpecLoader;
    private final OpenApiToolImporter openApiToolImporter;

    @PostConstruct
    public void register() {
        if (!properties.isEnabled()) {
            log.info("[T] REST API tool source disabled (langur.rest-api-tools.enabled=false)");
            return;
        }
        properties.getCredentials().forEach(this::loadCredential);
        properties.getTools().forEach(this::loadTool);
        int discovered = importOpenApi();
        log.info("[T] REST API tool source enabled: {} credential(s), {} manual tool(s), {} OpenAPI tool(s)",
                properties.getCredentials().size(), properties.getTools().size(), discovered);
    }

    /** 遍历 OpenAPI 来源自动发现并注册工具，返回成功注册数；单来源失败降级。 */
    private int importOpenApi() {
        int total = 0;
        for (RestApiToolProperties.OpenApiSource source : properties.getOpenapi()) {
            try {
                Map<String, Object> document = openApiSpecLoader.load(source);
                List<RestApiToolSpec> specs = openApiToolImporter.toSpecs(document, source);
                specs.forEach(this::registerSpec);
                total += specs.size();
                log.info("[T] OpenAPI auto-discovery [{}]: {} tool(s) registered", source.getServiceName(), specs.size());
            } catch (Exception e) {
                log.warn("[T] OpenAPI auto-discovery [{}] failed, skipped: {}",
                        source.getServiceName(), e.getMessage());
            }
        }
        return total;
    }

    private void loadCredential(RestApiToolProperties.CredentialProps props) {
        credentialVault.put(Credential.builder()
                .ref(props.getRef())
                .type(parseType(props.getType()))
                .headerName(props.getHeaderName())
                .username(props.getUsername())
                .secret(props.getSecret())
                .tokenUrl(props.getTokenUrl())
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .scope(props.getScope())
                .build());
    }

    private void loadTool(RestApiToolProperties.ToolProps props) {
        registerSpec(RestApiToolSpec.builder()
                .serviceName(props.getServiceName())
                .operationId(props.getOperationId())
                .method(props.getMethod())
                .urlTemplate(props.getUrlTemplate())
                .description(null)
                .headers(props.getHeaders())
                .credentialRef(props.getCredentialRef())
                .inputSchema(props.getInputSchema())
                .allowedHosts(props.getAllowedHosts())
                .permission(props.getPermission())
                .riskLevel(props.getRiskLevel())
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .build());
    }

    /** 注册单个工具规格进目录与 T 组件统一注册中心（手动/OpenAPI 共用）。 */
    private void registerSpec(RestApiToolSpec spec) {
        catalog.register(spec);
        toolRegistry.register(toDefinition(spec));
        log.info("[T] registered REST API tool into unified registry: {}", spec.toolId());
    }

    private ToolDefinitionEntity toDefinition(RestApiToolSpec spec) {
        String description = spec.getDescription() != null && !spec.getDescription().isBlank()
                ? spec.getDescription() : "REST API tool " + spec.toolId();
        return ToolDefinitionEntity.builder()
                .toolId(spec.toolId())
                .description(description)
                .inputSchema(spec.getInputSchema())
                .outputSchema(null)
                .permission(spec.getPermission())
                .riskLevel(parseRisk(spec.getRiskLevel()))
                .whitelist(List.of())
                .rateLimitPerMinute(null)
                .timeout(spec.getTimeout())
                .source(ToolSource.REST_API)
                .build();
    }

    private CredentialType parseType(String type) {
        try {
            return type == null ? CredentialType.BEARER : CredentialType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[T] unknown credential type [{}], fallback BEARER", type);
            return CredentialType.BEARER;
        }
    }

    private RiskLevel parseRisk(String risk) {
        try {
            return risk == null ? RiskLevel.LOW : RiskLevel.valueOf(risk.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.LOW;
        }
    }
}
