package org.skylark.langur.infrastructure.harness.tool.rest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API 工具源配置（§7.4）- {@code langur.rest-api-tools}。
 * <p>手动 YAML 配置方式注册；OpenAPI Spec 自动发现可作为后续增强在同一注册器接入。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.rest-api-tools")
public class RestApiToolProperties {

    /** 总开关，默认关闭（未配置任何工具时不产生副作用）。 */
    private boolean enabled = false;

    private List<CredentialProps> credentials = new ArrayList<>();
    private List<ToolProps> tools = new ArrayList<>();

    @Data
    public static class CredentialProps {
        private String ref;
        /** BEARER / API_KEY / BASIC。 */
        private String type;
        private String headerName;
        private String username;
        private String secret;
    }

    @Data
    public static class ToolProps {
        private String serviceName;
        private String operationId;
        private String method = "GET";
        private String urlTemplate;
        private Map<String, String> headers;
        private String credentialRef;
        private Map<String, Object> inputSchema;
        private List<String> allowedHosts;
        private String permission;
        private String riskLevel = "LOW";
        private long timeoutSeconds = 30;
    }
}
