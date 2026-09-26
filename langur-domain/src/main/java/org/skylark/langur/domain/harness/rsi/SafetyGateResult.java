package org.skylark.langur.domain.harness.rsi;

/**
 * 安全平面守门结果（R-G）。{@link RsiSafetyPlane} 每次状态迁移的裁定：放行（{@code allowed}）或拒绝
 * （{@code reason} 供告警/审计）。纯 JDK record，零外部依赖（P1）。</p>
 */
public record SafetyGateResult(boolean allowed, String reason) {

    public SafetyGateResult {
        reason = (reason == null) ? "" : reason;
    }

    public static SafetyGateResult allowed(String reason) {
        return new SafetyGateResult(true, reason);
    }

    public static SafetyGateResult rejected(String reason) {
        return new SafetyGateResult(false, reason);
    }
}
