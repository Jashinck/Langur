package org.skylark.langur.domain.harness.rsi;

/**
 * 工具自扩展校验器（R5）——候选工具注册前的<b>离线确定性护栏</b>（沙箱回归的确定性部分）。
 * <p>三道审查，任一不过即拒绝（P11/P12）：① 空候选拒绝；② <b>端点安全（SSRF 红线）</b>——端点解析为
 * 内网/环回/链路本地地址即拒绝（越权/内网候选不生效，镜像 infra {@code SsrfGuard} 红线，纯 JDK 判定）；
 * ③ <b>高危强制人审</b>——{@code HIGH/CRITICAL} 候选须 {@code humanApproved=true} 方可放行（未审批工具不生效）。
 * 纯 JDK 零外部依赖（P1）。</p>
 */
public class ToolExtensionValidator {

    /** 校验候选。 */
    public ToolExtensionVerdict validate(ToolExtensionCandidate candidate, boolean humanApproved) {
        if (candidate == null || candidate.toolId().isBlank()) {
            return ToolExtensionVerdict.rejected(ToolExtensionVerdict.Reason.REJECTED_EMPTY, "empty candidate");
        }
        if (isUnsafeEndpoint(candidate.endpoint())) {
            return ToolExtensionVerdict.rejected(ToolExtensionVerdict.Reason.REJECTED_UNSAFE_ENDPOINT,
                    "unsafe (internal/loopback) endpoint: " + candidate.endpoint());
        }
        if (candidate.isHighRisk() && !humanApproved) {
            return ToolExtensionVerdict.rejected(ToolExtensionVerdict.Reason.REJECTED_CRITICAL_WITHOUT_APPROVAL,
                    "high-risk tool requires human approval");
        }
        return ToolExtensionVerdict.accepted();
    }

    /** 解析端点主机，判内网/环回/链路本地（纯 JDK，镜像 SsrfGuard 红线）。 */
    static boolean isUnsafeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        String host = extractHost(endpoint);
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".localhost")) {
            return true;
        }
        // IPv6 环回
        if ("::1".equals(host) || "[::1]".equals(host)) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length == 4 && isNumeric(parts[0]) && isNumeric(parts[1])
                && isNumeric(parts[2]) && isNumeric(parts[3])) {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 127 || a == 10 || a == 0) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 169 && b == 254) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
        }
        return false;
    }

    private static String extractHost(String endpoint) {
        String s = endpoint.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(']') < 0) {
            s = s.substring(0, colon);
        }
        return s;
    }

    private static boolean isNumeric(String part) {
        if (part == null || part.isEmpty()) {
            return false;
        }
        for (char c : part.toCharArray()) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
