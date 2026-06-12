package org.skylark.langur.infrastructure.persistence.jpa.impl;

import org.skylark.langur.domain.model.execution.AgentRunTask;
import org.skylark.langur.domain.model.execution.RunTaskStatus;
import org.skylark.langur.domain.repository.AgentRunTaskRepository;
import org.skylark.langur.infrastructure.persistence.jpa.entity.AgentRunTaskDO;
import org.skylark.langur.infrastructure.persistence.jpa.repository.AgentRunTaskJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "langur.repository.type", havingValue = "jpa")
public class JpaAgentRunTaskRepository implements AgentRunTaskRepository {

    private final AgentRunTaskJpaRepository taskJpaRepository;

    public JpaAgentRunTaskRepository(AgentRunTaskJpaRepository taskJpaRepository) {
        this.taskJpaRepository = taskJpaRepository;
    }

    @Override
    public void save(AgentRunTask task) {
        taskJpaRepository.save(toEntity(task));
    }

    @Override
    public Optional<AgentRunTask> findById(String taskId) {
        return taskJpaRepository.findById(taskId).map(this::toDomain);
    }

    @Override
    public List<AgentRunTask> findByAgentId(String agentId) {
        return taskJpaRepository.findByAgentId(agentId).stream().map(this::toDomain).toList();
    }

    private AgentRunTaskDO toEntity(AgentRunTask task) {
        AgentRunTaskDO entity = new AgentRunTaskDO();
        entity.setTaskId(task.getTaskId());
        entity.setAgentId(task.getAgentId());
        entity.setUserId(task.getUserId());
        entity.setTenantId(task.getTenantId());
        entity.setSessionId(task.getSessionId());
        entity.setStatus(task.getStatus().name());
        entity.setResultSummary(task.getResultSummary());
        entity.setLastError(task.getLastError());
        entity.setCreatedAt(task.getCreatedAt());
        entity.setUpdatedAt(task.getUpdatedAt());
        return entity;
    }

    private AgentRunTask toDomain(AgentRunTaskDO entity) {
        return AgentRunTask.restore(
                entity.getTaskId(),
                entity.getAgentId(),
                entity.getUserId(),
                entity.getTenantId(),
                entity.getSessionId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                RunTaskStatus.valueOf(entity.getStatus()),
                entity.getResultSummary(),
                entity.getLastError());
    }
}
