package org.skylark.langur.domain.harness.state;

import java.util.Optional;

/**
 * S 组件 - 状态仓储（端口），基础设施层提供内存/Redis/MySQL 分层实现
 */
public interface TaskStateRepository {

    void save(TaskState state);

    Optional<TaskState> findById(String taskId);
}
