package org.skylark.langur.domain.harness.execution;

/**
 * 执行任务状态机
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    TERMINATED
}
