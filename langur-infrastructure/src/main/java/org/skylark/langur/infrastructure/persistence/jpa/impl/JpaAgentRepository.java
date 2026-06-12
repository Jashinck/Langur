package org.skylark.langur.infrastructure.persistence.jpa.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.agent.AgentId;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.repository.AgentRepository;
import org.skylark.langur.infrastructure.persistence.jpa.JsonValueMapper;
import org.skylark.langur.infrastructure.persistence.jpa.entity.AgentDO;
import org.skylark.langur.infrastructure.persistence.jpa.repository.AgentJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "langur.repository.type", havingValue = "jpa")
public class JpaAgentRepository implements AgentRepository {

    private final AgentJpaRepository agentJpaRepository;
    private final JsonValueMapper jsonValueMapper;

    public JpaAgentRepository(AgentJpaRepository agentJpaRepository, JsonValueMapper jsonValueMapper) {
        this.agentJpaRepository = agentJpaRepository;
        this.jsonValueMapper = jsonValueMapper;
    }

    @Override
    public void save(Agent agent) {
        agentJpaRepository.save(toEntity(agent));
    }

    @Override
    public Optional<Agent> findById(AgentId id) {
        return agentJpaRepository.findById(id.getValue()).map(this::toDomain);
    }

    @Override
    public List<Agent> findAll() {
        return agentJpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void delete(AgentId id) {
        agentJpaRepository.deleteById(id.getValue());
    }

    private AgentDO toEntity(Agent agent) {
        AgentDO entity = new AgentDO();
        entity.setId(agent.getId().getValue());
        entity.setName(agent.getConfig().getName());
        entity.setDescription(agent.getConfig().getDescription());
        entity.setSystemPrompt(agent.getConfig().getSystemPrompt());
        entity.setModel(agent.getConfig().getModel());
        entity.setTemperature(agent.getConfig().getTemperature());
        entity.setMaxIterations(agent.getConfig().getMaxIterations());
        entity.setMaxTokens(agent.getConfig().getMaxTokens());
        entity.setStatus(agent.getStatus().name());
        entity.setIterationCount(agent.getIterationCount());
        entity.setLastError(agent.getLastError());
        entity.setConversationHistory(jsonValueMapper.write(agent.getConversationHistory()));
        entity.setToolNames(jsonValueMapper.write(agent.getRegisteredToolNames()));
        entity.setCreatedAt(agent.getCreatedAt());
        entity.setUpdatedAt(agent.getUpdatedAt());
        return entity;
    }

    private Agent toDomain(AgentDO entity) {
        AgentConfig config = AgentConfig.builder()
                .name(entity.getName())
                .description(entity.getDescription())
                .systemPrompt(entity.getSystemPrompt())
                .model(entity.getModel())
                .temperature(entity.getTemperature())
                .maxIterations(entity.getMaxIterations())
                .maxTokens(entity.getMaxTokens())
                .build();
        List<String> toolNames = jsonValueMapper.read(entity.getToolNames(), new TypeReference<List<String>>() {}, new ArrayList<>());
        List<Map<String, String>> history = jsonValueMapper.read(
                entity.getConversationHistory(), new TypeReference<List<Map<String, String>>>() {}, new ArrayList<>());
        return Agent.restore(
                AgentId.of(entity.getId()),
                config,
                AgentStatus.valueOf(entity.getStatus()),
                toolNames,
                history,
                entity.getIterationCount(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
