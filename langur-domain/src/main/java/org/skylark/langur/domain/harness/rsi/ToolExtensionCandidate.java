package org.skylark.langur.domain.harness.rsi;

/**
 * 工具自扩展候选（R5，P11）——能力缺口检索到的<b>候选</b>工具，默认不生效，须经沙箱回归 + 强制人审。
 * <p>承载工具 ID、来源（{@code mcp}/{@code rest}）、端点、描述、风险等级与所填补的能力缺口。
 * 纯 JDK record，零外部依赖（P1）；构造时收敛缺省。端点安全（内网/环回）由
 * {@link ToolExtensionValidator} 拒绝（越权/内网候选被 SSRF/权限层拒绝，P11/P12）。</p>
 *
 * @param toolId      工具 ID（注册进 T 组件统一注册中心）
 * @param source      来源（{@code mcp} | {@code rest}）
 * @param endpoint    服务端点（SSRF 审查对象）
 * @param description 描述
 * @param riskLevel   风险等级（LOW/MEDIUM/HIGH/CRITICAL）
 * @param capability  所填补的能力缺口关键词（溯源）
 */
public record ToolExtensionCandidate(String toolId,
                                     String source,
                                     String endpoint,
                                     String description,
                                     String riskLevel,
                                     String capability) {

    public ToolExtensionCandidate {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("ToolExtensionCandidate toolId must not be blank");
        }
        source = (source == null || source.isBlank()) ? "mcp" : source;
        endpoint = (endpoint == null) ? "" : endpoint;
        description = (description == null) ? "" : description;
        riskLevel = (riskLevel == null || riskLevel.isBlank()) ? "LOW" : riskLevel;
        capability = (capability == null) ? "" : capability;
    }

    public static ToolExtensionCandidate of(String toolId, String source, String endpoint,
                                            String description, String riskLevel, String capability) {
        return new ToolExtensionCandidate(toolId, source, endpoint, description, riskLevel, capability);
    }

    /** 是否高危（高危须强制人审）。 */
    public boolean isHighRisk() {
        String r = riskLevel.toUpperCase();
        return "HIGH".equals(r) || "CRITICAL".equals(r);
    }
}
