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

/**
 * REST API 工具注册器（§8.1 启动阶段）- 将配置的 REST API 工具装载进凭证库、目录与 T 组件注册中心。
 * <p>注册后与本地工具无差别，统一经四层校验链管控；未启用时不产生任何副作用。</p>
 */
@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class RestApiToolRegistrar {

    private final RestApiToolProperties properties;
    private final RestApiToolCatalog catalog;
    private final ToolRegistry toolRegistry;
    private final InMemoryCredentialVault credentialVault;

    @PostConstruct
    public void register() {
        if (!properties.isEnabled()) {
            log.info("[T] REST API tool source disabled (langur.rest-api-tools.enabled=false)");
            return;
        }
        properties.getCredentials().forEach(this::loadCredential);
        properties.getTools().forEach(this::loadTool);
        log.info("[T] REST API tool source enabled: {} credential(s), {} tool(s)",
                properties.getCredentials().size(), properties.getTools().size());
    }

    private void loadCredential(RestApiToolProperties.CredentialProps props) {
        credentialVault.put(Credential.builder()
                .ref(props.getRef())
                .type(parseType(props.getType()))
                .headerName(props.getHeaderName())
                .username(props.getUsername())
                .secret(props.getSecret())
                .build());
    }

    private void loadTool(RestApiToolProperties.ToolProps props) {
        RestApiToolSpec spec = RestApiToolSpec.builder()
                .serviceName(props.getServiceName())
                .operationId(props.getOperationId())
                .method(props.getMethod())
                .urlTemplate(props.getUrlTemplate())
                .headers(props.getHeaders())
                .credentialRef(props.getCredentialRef())
                .inputSchema(props.getInputSchema())
                .allowedHosts(props.getAllowedHosts())
                .permission(props.getPermission())
                .riskLevel(props.getRiskLevel())
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .build();
        catalog.register(spec);
        toolRegistry.register(toDefinition(spec));
        log.info("[T] registered REST API tool into unified registry: {}", spec.toolId());
    }

    private ToolDefinitionEntity toDefinition(RestApiToolSpec spec) {
        return ToolDefinitionEntity.builder()
                .toolId(spec.toolId())
                .description("REST API tool " + spec.toolId())
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
