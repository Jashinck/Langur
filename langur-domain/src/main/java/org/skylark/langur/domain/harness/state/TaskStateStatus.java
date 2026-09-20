package org.skylark.langur.domain.harness.state;

/**
 * 任务状态机
 */
public enum TaskStateStatus {
    INIT,
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    ROLLED_BACK
}
