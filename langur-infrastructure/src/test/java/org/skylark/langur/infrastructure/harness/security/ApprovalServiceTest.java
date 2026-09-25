package org.skylark.langur.infrastructure.harness.security;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.security.ApprovalStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H10 - 审批回调服务测试。
 */
class ApprovalServiceTest {

    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final ApprovalService service = new ApprovalService(store);

    private ApprovalRequest seed() {
        ApprovalRequest request = ApprovalRequest.of("r1", "trace-1", "agent-1", "db.drop", "critical");
        store.save(request);
        return request;
    }

    @Test
    void shouldApprovePendingRequest() {
        seed();
        assertTrue(service.approve("r1", "admin", "ok"));
        assertEquals(ApprovalStatus.APPROVED, service.status("r1").orElseThrow());
    }

    @Test
    void shouldDenyPendingRequest() {
        seed();
        assertTrue(service.deny("r1", "admin", "too risky"));
        assertEquals(ApprovalStatus.DENIED, service.status("r1").orElseThrow());
    }

    @Test
    void shouldReturnFalseForUnknownRequest() {
        assertFalse(service.approve("missing", "admin", "ok"));
        assertFalse(service.deny("missing", "admin", "no"));
        assertTrue(service.status("missing").isEmpty());
    }
}
