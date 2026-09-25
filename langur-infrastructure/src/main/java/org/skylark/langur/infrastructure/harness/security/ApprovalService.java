package org.skylark.langur.infrastructure.harness.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.security.ApprovalStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * H10 高危审批 - 审批回调服务。
 * <p>承接外部审批系统/人工回调，流转审批单状态。审批通过后，被挂起的 CRITICAL 工具调用
 * 在下一次执行（断点续跑）时经 {@code CriticalApprovalValidator} 放行，从快照恢复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalPort approvalPort;

    /** 审批通过。 */
    public boolean approve(String requestId, String decisionBy, String comment) {
        return decide(requestId, true, decisionBy, comment);
    }

    /** 审批驳回。 */
    public boolean deny(String requestId, String decisionBy, String comment) {
        return decide(requestId, false, decisionBy, comment);
    }

    /** 查询审批单状态。 */
    public Optional<ApprovalStatus> status(String requestId) {
        return approvalPort.findById(requestId).map(ApprovalRequest::getStatus);
    }

    private boolean decide(String requestId, boolean approve, String decisionBy, String comment) {
        Optional<ApprovalRequest> found = approvalPort.findById(requestId);
        if (found.isEmpty()) {
            log.warn("[H10] approval callback for unknown request [{}]", requestId);
            return false;
        }
        ApprovalRequest request = found.get();
        if (approve) {
            request.approve(decisionBy, comment);
        } else {
            request.deny(decisionBy, comment);
        }
        approvalPort.save(request);
        log.info("[H10] approval request [{}] -> {} by [{}]", requestId, request.getStatus(), decisionBy);
        return true;
    }
}
