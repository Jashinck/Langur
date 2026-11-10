package org.skylark.langur.domain.model.execution;

public enum RunTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * 终态：完成/失败/已取消。终态任务不可再取消，仅失败/已取消可重试。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
