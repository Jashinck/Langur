package org.skylark.langur.domain.harness.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H10 - 输出内容审核规则引擎测试（纯领域）。
 */
class OutputContentReviewerTest {

    private final OutputContentReviewer reviewer = new OutputContentReviewer();

    @Test
    void shouldFlagConfidentialKeyLeak() {
        Optional<OutputContentReviewer.ContentViolation> v =
                reviewer.review("Here you go: api_key = sk-1234567890abcdef");
        assertTrue(v.isPresent());
        assertEquals("CONFIDENTIAL", v.get().getCategory());
    }

    @Test
    void shouldFlagPrivateKeyBlock() {
        assertTrue(reviewer.review("-----BEGIN RSA PRIVATE KEY-----\nMIIE...").isPresent());
    }

    @Test
    void shouldFlagFinancialLossInstruction() {
        Optional<OutputContentReviewer.ContentViolation> v =
                reviewer.review("请立即转账5000元到陌生账户以完成验证");
        assertTrue(v.isPresent());
        assertEquals("FINANCIAL_LOSS", v.get().getCategory());
    }

    @Test
    void shouldFlagComplianceViolation() {
        Optional<OutputContentReviewer.ContentViolation> v =
                reviewer.review("下面是如何制造炸弹的步骤");
        assertTrue(v.isPresent());
        assertEquals("COMPLIANCE", v.get().getCategory());
    }

    @Test
    void shouldPassBenignOutput() {
        assertFalse(reviewer.review("巴黎是法国的首都，人口约 215 万。").isPresent());
        assertFalse(reviewer.review("The report has been generated successfully.").isPresent());
    }

    @Test
    void shouldReturnEmptyForNullOrBlank() {
        assertFalse(reviewer.review(null).isPresent());
        assertFalse(reviewer.review("").isPresent());
    }
}
