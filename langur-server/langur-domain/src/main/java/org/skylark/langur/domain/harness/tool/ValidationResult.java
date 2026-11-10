package org.skylark.langur.domain.harness.tool;

import lombok.Getter;

/**
 * 工具校验结果 - 责任链节点的输出
 */
@Getter
public class ValidationResult {

    private final boolean passed;
    private final String reason;

    private ValidationResult(boolean passed, String reason) {
        this.passed = passed;
        this.reason = reason;
    }

    public static ValidationResult pass() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult reject(String reason) {
        return new ValidationResult(false, reason);
    }
}
