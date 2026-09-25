package org.skylark.langur.domain.harness.security;

import lombok.Getter;

import java.time.Instant;

/**
 * H10 高危审批 - 审批请求单（CRITICAL 风险工具调用触发）。
 * <p>不可变值对象；由校验链在拒绝高危调用时创建并交由 {@link ApprovalPort} 暂存，
 * 承载挂起-回调-恢复所需的关联信息。</p>
 */
@Getter
public class ApprovalRequest {

    private final String requestId;
    private final String traceId;
    private final String caller;
    private final String toolId;
    private final String reason;
    private final Instant createdAt;
    private ApprovalStatus status;
    private String decisionBy;
    private String decisionComment;
    private Instant decidedAt;

    private ApprovalRequest(String requestId, String traceId, String caller,
                            String toolId, String reason) {
        this.requestId = requestId;
        this.traceId = traceId;
        this.caller = caller;
        this.toolId = toolId;
        this.reason = reason;
        this.createdAt = Instant.now();
        this.status = ApprovalStatus.PENDING;
    }

    public static ApprovalRequest of(String requestId, String traceId, String caller,
                                     String toolId, String reason) {
        return new ApprovalRequest(requestId, traceId, caller, toolId, reason);
    }

    /** 审批通过（幂等：仅 PENDING 可流转）。 */
    public void approve(String decisionBy, String comment) {
        decide(ApprovalStatus.APPROVED, decisionBy, comment);
    }

    /** 审批驳回（幂等：仅 PENDING 可流转）。 */
    public void deny(String decisionBy, String comment) {
        decide(ApprovalStatus.DENIED, decisionBy, comment);
    }

    private void decide(ApprovalStatus target, String decisionBy, String comment) {
        if (this.status != ApprovalStatus.PENDING) {
            return;
        }
        this.status = target;
        this.decisionBy = decisionBy;
        this.decisionComment = comment;
        this.decidedAt = Instant.now();
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    public boolean isApproved() {
        return status == ApprovalStatus.APPROVED;
    }
}
