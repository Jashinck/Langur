package org.skylark.langur.infrastructure.persistence;

import org.skylark.langur.domain.model.execution.AgentRunTask;
import org.skylark.langur.domain.repository.AgentRunTaskRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryAgentRunTaskRepository implements AgentRunTaskRepository {

    private final Map<String, AgentRunTask> store = new ConcurrentHashMap<>();

    @Override
    public void save(AgentRunTask task) {
        store.put(task.getTaskId(), task);
    }

    @Override
    public Optional<AgentRunTask> findById(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<AgentRunTask> findByAgentId(String agentId) {
        List<AgentRunTask> results = new ArrayList<>();
        for (AgentRunTask task : store.values()) {
            if (agentId.equals(task.getAgentId())) {
                results.add(task);
            }
        }
        return results;
    }
}
