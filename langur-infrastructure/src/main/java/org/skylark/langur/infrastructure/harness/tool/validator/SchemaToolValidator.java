package org.skylark.langur.infrastructure.harness.tool.validator;

import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 四层校验链 [2] - Schema 强校验：入参必填项与结构检查
 */
@Component
public class SchemaToolValidator implements ToolValidator {

    @Override
    @SuppressWarnings("unchecked")
    public ValidationResult validate(ToolCallRequest request, ToolDefinitionEntity definition) {
        Map<String, Object> schema = definition.getInputSchema();
        if (schema == null || schema.isEmpty()) {
            return ValidationResult.pass();
        }
        Object requiredObj = schema.get("required");
        if (!(requiredObj instanceof List<?> required)) {
            return ValidationResult.pass();
        }
        Map<String, Object> arguments = request.getArguments() != null ? request.getArguments() : Map.of();
        for (Object field : required) {
            String name = String.valueOf(field);
            if (!arguments.containsKey(name) || arguments.get(name) == null) {
                return ValidationResult.reject(
                        "missing required argument [" + name + "] for tool [" + definition.getToolId() + "]");
            }
        }
        return ValidationResult.pass();
    }

    @Override
    public int order() {
        return 200;
    }
}
