package org.skylark.langur.domain.harness.tool;

/**
 * 工具校验器 - 责任链模式。
 * <p>四层校验链：白名单准入 → Schema 强校验 → 动态权限 → 沙箱隔离执行。
 * 实现类通过 {@link #order()} 决定执行顺序，落于基础设施层。</p>
 */
public interface ToolValidator {

    ValidationResult validate(ToolCallRequest request, ToolDefinitionEntity definition);

    int order();
}
