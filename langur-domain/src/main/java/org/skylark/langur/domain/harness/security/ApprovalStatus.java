package org.skylark.langur.domain.harness.security;

/**
 * H10 高危审批 - 审批单状态机。
 */
public enum ApprovalStatus {
    /** 待审批：任务应挂起等待回调。 */
    PENDING,
    /** 已通过：可从快照恢复执行。 */
    APPROVED,
    /** 已驳回：终止工具调用。 */
    DENIED
}
