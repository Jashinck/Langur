package org.skylark.langur.infrastructure.harness.tool.rest;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * REST API 工具规格（§7.4）- 存量 HTTP API 零改造工具化的注册单元。
 * <p>工具 ID 规范：{@code api:{serviceName}:{operationId}}。urlTemplate 支持 {@code {param}} 占位符，
 * 由调用参数替换（路径参数）；未匹配的占位参数作为 query 追加。</p>
 */
@Getter
@Builder
public class RestApiToolSpec {

    private final String serviceName;
    private final String operationId;
    private final String method;
    private final String urlTemplate;
    /** 静态请求头（凭证头由 CredentialVault 追加）。 */
    private final Map<String, String> headers;
    /** 凭证引用，对应 CredentialVault 中的条目；为空表示匿名调用。 */
    private final String credentialRef;
    /** 入参 JSON Schema，供四层校验链 Schema 层使用。 */
    private final Map<String, Object> inputSchema;
    /** 主机白名单（SSRF 防护的可信例外）。 */
    private final List<String> allowedHosts;
    private final String permission;
    private final String riskLevel;
    @Builder.Default
    private final Duration timeout = Duration.ofSeconds(30);

    public String toolId() {
        return "api:" + serviceName + ":" + operationId;
    }
}
