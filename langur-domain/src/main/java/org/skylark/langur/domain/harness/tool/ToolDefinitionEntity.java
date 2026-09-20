package org.skylark.langur.domain.harness.tool;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * T 组件聚合根 - 工具定义实体（九元组注册标准，缺一不可）。
 * <p>ID / 描述 / 入参Schema / 出参Schema / 权限 / 风险 / 白名单 / 限流 / 超时</p>
 */
@Getter
@Builder
public class ToolDefinitionEntity {

    private final String toolId;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final String permission;
    private final RiskLevel riskLevel;
    private final List<String> whitelist;
    private final Integer rateLimitPerMinute;
    private final Duration timeout;
    private final ToolSource source;

    public boolean isInvokableBy(String caller) {
        return whitelist == null || whitelist.isEmpty() || whitelist.contains(caller);
    }

    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }
}
