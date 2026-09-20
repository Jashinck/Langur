package org.skylark.langur.infrastructure.harness.tool.validator;

import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.springframework.stereotype.Component;

/**
 * 四层校验链 [1] - 白名单准入：未注册不可见、未授权不可调用
 */
@Component
public class WhitelistToolValidator implements ToolValidator {

    @Override
    public ValidationResult validate(ToolCallRequest request, ToolDefinitionEntity definition) {
        if (!definition.isInvokableBy(request.getCaller())) {
            return ValidationResult.reject(
                    "caller [" + request.getCaller() + "] not in whitelist of tool [" + definition.getToolId() + "]");
        }
        return ValidationResult.pass();
    }

    @Override
    public int order() {
        return 100;
    }
}
