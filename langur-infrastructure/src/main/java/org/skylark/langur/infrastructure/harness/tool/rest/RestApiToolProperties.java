package org.skylark.langur.infrastructure.harness.tool.rest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API 工具源配置（§7.4）- {@code langur.rest-api-tools}。
 * <p>支持两种注册方式：手动 YAML 工具列表（{@code tools}）与 OpenAPI 文档自动发现（{@code openapi}，H7）。
 * 凭证 {@code credentials} 支持 BEARER/API_KEY/BASIC/OAUTH2，密钥字段可用 {@code env:}/{@code prop:}/{@code kms:} 引用。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.rest-api-tools")
public class RestApiToolProperties {

    /** 总开关，默认关闭（未配置任何工具时不产生副作用）。 */
    private boolean enabled = false;

    private List<CredentialProps> credentials = new ArrayList<>();
    private List<ToolProps> tools = new ArrayList<>();
    /** OpenAPI 自动发现来源（H7）。 */
    private List<OpenApiSource> openapi = new ArrayList<>();

    @Data
    public static class CredentialProps {
        private String ref;
        /** BEARER / API_KEY / BASIC / OAUTH2。 */
        private String type;
        private String headerName;
        private String username;
        /** 密钥；可为 env:/prop:/kms: 引用（OAUTH2 时忽略，用 clientSecret）。 */
        private String secret;
        // ---- OAUTH2 (client_credentials) ----
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;
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

    /**
     * OpenAPI 自动发现来源（H7）- 从内联文档 / classpath 资源 / 远端 URL 解析并批量注册工具。
     * <p>{@code spec}（内联，JSON 或 YAML 自动识别）优先，其次 {@code specResource}（classpath），
     * 最后 {@code specUrl}（远端，经 SSRF 校验）。{@code baseUrl} 覆盖文档 servers[0].url。</p>
     */
    @Data
    public static class OpenApiSource {
        /** 服务名，参与工具 ID：api:{serviceName}:{operationId}。 */
        private String serviceName;
        /** 内联 OpenAPI 文档（JSON/YAML）。 */
        private String spec;
        /** classpath 下的 OpenAPI 文档路径。 */
        private String specResource;
        /** 远端 OpenAPI 文档 URL（SSRF 校验后拉取）。 */
        private String specUrl;
        /** 覆盖文档 servers[0].url 的基地址。 */
        private String baseUrl;
        /** 该来源下所有工具默认凭证引用。 */
        private String credentialRef;
        /** operationId 白名单；为空表示发现全部操作。 */
        private List<String> includeOperations = new ArrayList<>();
        private List<String> allowedHosts;
        private String permission;
        private String riskLevel = "LOW";
        private long timeoutSeconds = 30;
    }
}
