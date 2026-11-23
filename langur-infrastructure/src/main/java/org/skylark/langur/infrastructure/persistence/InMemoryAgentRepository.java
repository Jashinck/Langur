package org.skylark.langur.infrastructure.persistence;

import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentId;
import org.skylark.langur.domain.repository.AgentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "langur.repository.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentRepository implements AgentRepository {

    private final Map<String, Agent> store = new ConcurrentHashMap<>();

    @Override
    public void save(Agent agent) {
        store.put(agent.getId().getValue(), agent);
    }

    @Override
    public Optional<Agent> findById(AgentId id) {
        return Optional.ofNullable(store.get(id.getValue()));
    }

    @Override
    public List<Agent> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void delete(AgentId id) {
        store.remove(id.getValue());
    }
}
