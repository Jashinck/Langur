package org.skylark.langur.infrastructure.harness.tool.validator;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.tool.RiskLevel;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 四层校验链 [3.5] - 高危审批（H10）。
 * <p>对 {@link RiskLevel#CRITICAL} 风险工具强制异步审批：无审批单则创建并挂起（拒绝本次调用），
 * PENDING 继续挂起，DENIED 终止，APPROVED 放行。放行后由既有快照/断点续跑机制从挂起点恢复。</p>
 * <p>审批后端未装配时对 CRITICAL 工具失败关闭（fail-closed），不放行最高风险调用（P10 安全兜底）。</p>
 */
@Slf4j
@Component
public class CriticalApprovalValidator implements ToolValidator {

    private ApprovalPort approvalPort;

    @Autowired(required = false)
    public void setApprovalPort(ApprovalPort approvalPort) {
        this.approvalPort = approvalPort;
    }

    @Override
    public ValidationResult validate(ToolCallRequest request, ToolDefinitionEntity definition) {
        if (definition.getRiskLevel() != RiskLevel.CRITICAL) {
            return ValidationResult.pass();
        }
        if (approvalPort == null) {
            return ValidationResult.reject(
                    "CRITICAL tool [" + definition.getToolId() + "] requires an approval backend, none configured");
        }
        Optional<ApprovalRequest> existing = approvalPort.findLatest(request.getTraceId(), definition.getToolId());
        if (existing.isPresent()) {
            ApprovalRequest req = existing.get();
            return switch (req.getStatus()) {
                case APPROVED -> ValidationResult.pass();
                case PENDING -> ValidationResult.reject(
                        "CRITICAL tool [" + definition.getToolId() + "] suspended pending approval ["
                                + req.getRequestId() + "]");
                case DENIED -> ValidationResult.reject(
                        "CRITICAL tool [" + definition.getToolId() + "] approval denied ["
                                + req.getRequestId() + "]");
            };
        }
        String requestId = UUID.randomUUID().toString();
        approvalPort.save(ApprovalRequest.of(requestId, request.getTraceId(), request.getCaller(),
                definition.getToolId(), "CRITICAL risk tool requires manual approval"));
        log.warn("[H10] CRITICAL tool [{}] suspended, approval request [{}] created for trace [{}]",
                definition.getToolId(), requestId, request.getTraceId());
        return ValidationResult.reject(
                "CRITICAL tool [" + definition.getToolId() + "] suspended pending approval [" + requestId + "]");
    }

    @Override
    public int order() {
        return 350;
    }
}
