package org.skylark.langur.infrastructure.harness.tool.validator;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.skylark.langur.infrastructure.harness.security.InMemoryApprovalStore;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H10 - CRITICAL 风险工具异步审批校验器测试。
 */
class CriticalApprovalValidatorTest {

    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final CriticalApprovalValidator validator = newValidator(store);

    private static CriticalApprovalValidator newValidator(InMemoryApprovalStore store) {
        CriticalApprovalValidator v = new CriticalApprovalValidator();
        v.setApprovalPort(store);
        return v;
    }

    private static ToolDefinitionEntity tool(RiskLevel risk) {
        return ToolDefinitionEntity.builder()
                .toolId("db.drop").description("drop table").permission("admin")
                .riskLevel(risk).source(ToolSource.LOCAL)
                .inputSchema(Map.of()).outputSchema(Map.of())
                .build();
    }

    private static ToolCallRequest request() {
        return ToolCallRequest.builder()
                .toolId("db.drop").caller("agent-1").traceId("trace-1")
                .arguments(Map.of()).build();
    }

    @Test
    void shouldPassNonCriticalRisk() {
        assertTrue(validator.validate(request(), tool(RiskLevel.HIGH)).isPassed());
        assertTrue(validator.validate(request(), tool(RiskLevel.LOW)).isPassed());
    }

    @Test
    void shouldSuspendCriticalAndCreateApprovalOnFirstCall() {
        ValidationResult result = validator.validate(request(), tool(RiskLevel.CRITICAL));
        assertFalse(result.isPassed());
        assertTrue(result.getReason().contains("suspended pending approval"));
        assertTrue(store.findLatest("trace-1", "db.drop").isPresent());
        assertTrue(store.findLatest("trace-1", "db.drop").get().isPending());
    }

    @Test
    void shouldStaySuspendedWhilePending() {
        validator.validate(request(), tool(RiskLevel.CRITICAL));
        ValidationResult second = validator.validate(request(), tool(RiskLevel.CRITICAL));
        assertFalse(second.isPassed());
        assertTrue(second.getReason().contains("pending approval"));
    }

    @Test
    void shouldPassAfterApprovalGranted() {
        validator.validate(request(), tool(RiskLevel.CRITICAL));
        ApprovalRequest req = store.findLatest("trace-1", "db.drop").orElseThrow();
        req.approve("admin", "ok");
        store.save(req);
        assertTrue(validator.validate(request(), tool(RiskLevel.CRITICAL)).isPassed());
    }

    @Test
    void shouldRejectAfterApprovalDenied() {
        validator.validate(request(), tool(RiskLevel.CRITICAL));
        ApprovalRequest req = store.findLatest("trace-1", "db.drop").orElseThrow();
        req.deny("admin", "no");
        store.save(req);
        ValidationResult result = validator.validate(request(), tool(RiskLevel.CRITICAL));
        assertFalse(result.isPassed());
        assertTrue(result.getReason().contains("denied"));
    }

    @Test
    void shouldFailClosedWhenNoApprovalBackend() {
        CriticalApprovalValidator bare = new CriticalApprovalValidator();
        ValidationResult result = bare.validate(request(), tool(RiskLevel.CRITICAL));
        assertFalse(result.isPassed());
        assertTrue(result.getReason().contains("approval backend"));
    }
}
