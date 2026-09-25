package org.skylark.langur.domain.harness.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H10 - 高危审批单状态机测试（纯领域）。
 */
class ApprovalRequestTest {

    @Test
    void shouldStartPending() {
        ApprovalRequest request = ApprovalRequest.of("r1", "trace-1", "caller", "tool-x", "critical");
        assertTrue(request.isPending());
        assertFalse(request.isApproved());
        assertEquals(ApprovalStatus.PENDING, request.getStatus());
    }

    @Test
    void shouldApproveFromPending() {
        ApprovalRequest request = ApprovalRequest.of("r1", "trace-1", "caller", "tool-x", "critical");
        request.approve("admin", "looks fine");
        assertTrue(request.isApproved());
        assertEquals("admin", request.getDecisionBy());
        assertEquals("looks fine", request.getDecisionComment());
    }

    @Test
    void shouldDenyFromPending() {
        ApprovalRequest request = ApprovalRequest.of("r1", "trace-1", "caller", "tool-x", "critical");
        request.deny("admin", "too risky");
        assertEquals(ApprovalStatus.DENIED, request.getStatus());
        assertFalse(request.isPending());
    }

    @Test
    void shouldNotOverrideDecisionOnceDecided() {
        ApprovalRequest request = ApprovalRequest.of("r1", "trace-1", "caller", "tool-x", "critical");
        request.approve("admin", "ok");
        request.deny("other", "no");
        assertTrue(request.isApproved());
        assertEquals("admin", request.getDecisionBy());
    }
}
