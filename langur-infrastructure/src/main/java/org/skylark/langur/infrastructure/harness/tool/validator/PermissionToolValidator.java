package org.skylark.langur.infrastructure.harness.tool.validator;

import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.springframework.stereotype.Component;

/**
 * 四层校验链 [3] - 动态权限：高危工具要求显式授权（风险等级驱动的准入策略）。
 * <p>生产环境可对接用户画像权限中心做细粒度动态鉴权。</p>
 */
@Component
public class PermissionToolValidator implements ToolValidator {

    @Override
    public ValidationResult validate(ToolCallRequest request, ToolDefinitionEntity definition) {
        if (definition.isHighRisk()) {
            boolean explicitlyGranted = definition.getWhitelist() != null
                    && definition.getWhitelist().contains(request.getCaller());
            if (!explicitlyGranted) {
                return ValidationResult.reject(
                        "high-risk tool [" + definition.getToolId() + "] requires explicit permission for caller ["
                                + request.getCaller() + "]");
            }
        }
        return ValidationResult.pass();
    }

    @Override
    public int order() {
        return 300;
    }
}
