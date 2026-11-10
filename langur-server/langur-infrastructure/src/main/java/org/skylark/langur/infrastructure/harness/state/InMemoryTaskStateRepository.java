package org.skylark.langur.infrastructure.harness.state;

import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * S 组件 - 内存状态存储实现（L1 内存层）。
 * <p>生产环境可替换为 Redis（热状态）/ MySQL（归档）分层实现。</p>
 */
@Repository
@ConditionalOnProperty(name = "langur.repository.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskStateRepository implements TaskStateRepository {

    private final ConcurrentMap<String, TaskState> store = new ConcurrentHashMap<>();

    @Override
    public void save(TaskState state) {
        store.put(state.getTaskId(), state);
    }

    @Override
    public Optional<TaskState> findById(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }
}
