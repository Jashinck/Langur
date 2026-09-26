package org.skylark.langur.infrastructure.harness.rsi;

import org.skylark.langur.domain.harness.rsi.ToolExtensionCandidate;
import org.skylark.langur.domain.harness.rsi.ToolExtensionVerdict;
import org.skylark.langur.domain.harness.rsi.ToolExtensionValidator;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolSource;

import java.util.List;
import java.util.Map;

/**
 * 工具自扩展注册器（R5）——候选工具经校验后注册进 T 组件统一注册中心（四层校验链元数据），并支持注销回滚。
 * <p>强制人工审批 + SSRF/权限护栏由 {@link ToolExtensionValidator}（domain）承载；本类只做"放行即注册、
 * 可注销"的落库动作（source=MCP/REST_API）。纯 JDK + Lombok，无 Spring 依赖，由 start 装配（默认关）。</p>
 */
public class ToolExtensionRegistrar {

    private final ToolExtensionValidator validator = new ToolExtensionValidator();

    /** 校验 + 注册：放行才写注册中心；高危候选须 {@code humanApproved=true}。返回是否注册。 */
    public boolean register(ToolExtensionCandidate candidate, boolean humanApproved, ToolRegistry registry) {
        if (registry == null) {
            return false;
        }
        ToolExtensionVerdict verdict = validator.validate(candidate, humanApproved);
        if (!verdict.isAccepted()) {
            return false;
        }
        registry.register(toDefinition(candidate));
        return true;
    }

    /** 注销（回滚/热更新移除）。 */
    public void unregister(String toolId, ToolRegistry registry) {
        if (toolId != null && registry != null) {
            registry.unregister(toolId);
        }
    }

    private ToolDefinitionEntity toDefinition(ToolExtensionCandidate candidate) {
        ToolSource source = "rest".equalsIgnoreCase(candidate.source()) ? ToolSource.REST_API : ToolSource.MCP;
        return ToolDefinitionEntity.builder()
                .toolId(candidate.toolId())
                .description(candidate.description())
                .inputSchema(Map.of())
                .outputSchema(null)
                .permission("")
                .riskLevel(parseRisk(candidate.riskLevel()))
                .whitelist(List.of())
                .rateLimitPerMinute(null)
                .timeout(null)
                .source(source)
                .build();
    }

    private RiskLevel parseRisk(String risk) {
        try {
            return risk == null || risk.isBlank() ? RiskLevel.LOW : RiskLevel.valueOf(risk.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.LOW;
        }
    }
}
