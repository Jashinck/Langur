package org.skylark.langur.infrastructure.harness.tool.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具源配置（§7.3）- {@code langur.mcp-tools}。
 * <p>默认关闭；启用后按 servers 逐个连接、发现工具并以 {@code mcp:{server}:{tool}} 注册进 T 组件。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.mcp-tools")
public class McpToolProperties {

    /** 总开关，默认关闭（未配置任何服务端时不产生副作用）。 */
    private boolean enabled = false;

    /** 心跳间隔秒数（<=0 关闭心跳）。 */
    private long heartbeatSeconds = 30;

    /** MCP 协议版本（initialize 握手声明）。 */
    private String protocolVersion = "2024-11-05";

    /** 单服务端连续失败达此阈值即熔断跳闸（H6 多 server 隔离）。 */
    private int failureThreshold = 3;

    /** 熔断冷却秒数：冷却期内快速失败，冷却后放行试探（半开）。 */
    private long circuitCooldownSeconds = 30;

    private List<ServerProps> servers = new ArrayList<>();

    @Data
    public static class ServerProps {
        /** 服务端逻辑名，参与工具 ID：mcp:{name}:{tool}。 */
        private String name;
        /** 传输类型（H6）：HTTP（默认）/ STDIO / SSE / WS。 */
        private McpTransportType transport = McpTransportType.HTTP;
        /** JSON-RPC over HTTP(S) 端点；SSE 时作为出站 POST 端点。 */
        private String url;
        /** SSE 入站事件流端点；为空时回退 {@link #url}。 */
        private String sseUrl;
        /** STDIO 传输：本地子进程命令（含参数），如 {@code ["npx","-y","@mcp/fs"]}。 */
        private List<String> command = new ArrayList<>();
        /** STDIO 传输：子进程附加环境变量。 */
        private Map<String, String> env = new HashMap<>();
        /** 静态请求头（凭证头由 CredentialVault 追加）。 */
        private Map<String, String> headers;
        /** 凭证引用，复用 REST 工具源凭证库；为空表示匿名。 */
        private String credentialRef;
        /** 主机白名单（SSRF 防护的可信例外）。 */
        private List<String> allowedHosts;
        /** 该服务端下所有工具默认权限标识。 */
        private String permission;
        /** 该服务端下所有工具默认风险等级（LOW/MEDIUM/HIGH/CRITICAL）。 */
        private String riskLevel = "LOW";
        private long timeoutSeconds = 30;
        /** 工具发现白名单；为空表示发现全部远端工具。 */
        private List<String> includeTools = new ArrayList<>();
    }
}
