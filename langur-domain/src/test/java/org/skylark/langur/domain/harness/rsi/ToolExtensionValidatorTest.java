package org.skylark.langur.domain.harness.rsi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 工具自扩展校验器离线确定性测试（无 Mockito）。
 * <p>覆盖：安全低危放行、环回/内网/链路本地端点拒绝（SSRF 红线）、高危强制人审、空候选拒绝。</p>
 */
class ToolExtensionValidatorTest {

    private final ToolExtensionValidator validator = new ToolExtensionValidator();

    private static ToolExtensionCandidate candidate(String endpoint, String riskLevel) {
        return ToolExtensionCandidate.of("t1", "rest", endpoint, "desc", riskLevel, "邮件");
    }

    @Test
    void shouldAcceptSafeLowRiskCandidate() {
        assertTrue(validator.validate(candidate("https://api.example.com/v1", "LOW"), false).isAccepted());
    }

    @Test
    void shouldRejectLoopbackEndpoint() {
        ToolExtensionVerdict verdict = validator.validate(candidate("http://127.0.0.1:8080/api", "LOW"), false);
        assertFalse(verdict.isAccepted());
        assertTrue(verdict.reason() == ToolExtensionVerdict.Reason.REJECTED_UNSAFE_ENDPOINT);
    }

    @Test
    void shouldRejectPrivateAndLinkLocalEndpoints() {
        assertFalse(validator.validate(candidate("http://192.168.1.10", "LOW"), false).isAccepted());
        assertFalse(validator.validate(candidate("http://10.0.0.5", "LOW"), false).isAccepted());
        assertFalse(validator.validate(candidate("http://169.254.169.254", "LOW"), false).isAccepted());
        assertFalse(validator.validate(candidate("http://localhost:8080", "LOW"), false).isAccepted());
    }

    @Test
    void shouldRequireApprovalForHighRisk() {
        ToolExtensionCandidate high = candidate("https://api.example.com", "HIGH");
        assertFalse(validator.validate(high, false).isAccepted(), "高危未经人审不得放行");
        assertTrue(validator.validate(high, true).isAccepted(), "高危经人审可放行");
    }

    @Test
    void shouldRejectNullCandidate() {
        assertFalse(validator.validate(null, false).isAccepted());
    }
}
