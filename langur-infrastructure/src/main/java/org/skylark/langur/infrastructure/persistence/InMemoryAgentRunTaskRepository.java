package org.skylark.langur.infrastructure.persistence;

import org.skylark.langur.domain.model.execution.AgentRunTask;
import org.skylark.langur.domain.repository.AgentRunTaskRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryAgentRunTaskRepository implements AgentRunTaskRepository {

    private final ConcurrentMap<String, AgentRunTask> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, java.util.Set<String>> agentTaskIds = new ConcurrentHashMap<>();

    @Override
    public void save(AgentRunTask task) {
        store.put(task.getTaskId(), task);
        agentTaskIds.computeIfAbsent(task.getAgentId(), key -> ConcurrentHashMap.newKeySet())
                .add(task.getTaskId());
    }

    @Override
    public Optional<AgentRunTask> findById(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<AgentRunTask> findByAgentId(String agentId) {
        java.util.Set<String> taskIds = agentTaskIds.get(agentId);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }

        return taskIds.stream()
                .map(store::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
