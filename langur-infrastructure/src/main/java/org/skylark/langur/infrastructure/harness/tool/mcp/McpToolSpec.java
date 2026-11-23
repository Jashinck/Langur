package org.skylark.langur.infrastructure.harness.tool.mcp;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具规格（§7.3）- 由 {@link McpClientManager} 发现远端工具后落地的注册单元。
 * <p>工具 ID 规范：{@code mcp:{server}:{tool}}，其中 server 为配置的服务端逻辑名，tool 为远端工具名。</p>
 */
@Getter
@Builder
public class McpToolSpec {

    private final String server;
    /** 远端工具名（tools/call 时使用）。 */
    private final String name;
    private final String description;
    /** 入参 JSON Schema，供四层校验链 Schema 层使用。 */
    private final Map<String, Object> inputSchema;
    private final String permission;
    private final String riskLevel;
    /** 主机白名单（SSRF 防护的可信例外）。 */
    private final List<String> allowedHosts;
    @Builder.Default
    private final Duration timeout = Duration.ofSeconds(30);

    public String toolId() {
        return "mcp:" + server + ":" + name;
    }
}
