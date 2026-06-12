package org.skylark.langur.infrastructure.persistence;

import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.repository.PlanRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryPlanRepository implements PlanRepository {

    private final Map<String, Plan> store = new ConcurrentHashMap<>();

    @Override
    public void save(Plan plan) {
        store.put(plan.getAgentId(), plan);
    }

    @Override
    public Optional<Plan> findByAgentId(String agentId) {
        return Optional.ofNullable(store.get(agentId));
    }

    @Override
    public void delete(String agentId) {
        store.remove(agentId);
    }
}
