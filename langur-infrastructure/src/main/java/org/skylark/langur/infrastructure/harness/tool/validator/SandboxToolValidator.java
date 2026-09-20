package org.skylark.langur.infrastructure.harness.tool.validator;

import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.springframework.stereotype.Component;

/**
 * 四层校验链 [4] - 沙箱准入：执行前的沙箱策略校验（超时约束必须存在）。
 * <p>实际的进程/网络隔离执行由调度器在受控线程内完成；此处做策略兜底。</p>
 */
@Component
public class SandboxToolValidator implements ToolValidator {

    @Override
    public ValidationResult validate(ToolCallRequest request, ToolDefinitionEntity definition) {
        if (definition.getTimeout() == null || definition.getTimeout().isNegative()
                || definition.getTimeout().isZero()) {
            return ValidationResult.reject(
                    "tool [" + definition.getToolId() + "] missing sandbox timeout constraint");
        }
        return ValidationResult.pass();
    }

    @Override
    public int order() {
        return 400;
    }
}
