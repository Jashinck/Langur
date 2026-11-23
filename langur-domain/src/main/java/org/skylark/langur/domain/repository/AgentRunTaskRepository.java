package org.skylark.langur.domain.repository;

import org.skylark.langur.domain.model.execution.AgentRunTask;

import java.util.List;
import java.util.Optional;

public interface AgentRunTaskRepository {
    void save(AgentRunTask task);

    Optional<AgentRunTask> findById(String taskId);

    List<AgentRunTask> findByAgentId(String agentId);
}
