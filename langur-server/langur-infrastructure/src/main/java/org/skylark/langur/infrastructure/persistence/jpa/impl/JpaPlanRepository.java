package org.skylark.langur.infrastructure.persistence.jpa.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.domain.repository.PlanRepository;
import org.skylark.langur.infrastructure.persistence.jpa.JsonValueMapper;
import org.skylark.langur.infrastructure.persistence.jpa.PlanStepSnapshot;
import org.skylark.langur.infrastructure.persistence.jpa.entity.PlanDO;
import org.skylark.langur.infrastructure.persistence.jpa.repository.PlanJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "langur.repository.type", havingValue = "jpa")
public class JpaPlanRepository implements PlanRepository {

    private final PlanJpaRepository planJpaRepository;
    private final JsonValueMapper jsonValueMapper;

    public JpaPlanRepository(PlanJpaRepository planJpaRepository, JsonValueMapper jsonValueMapper) {
        this.planJpaRepository = planJpaRepository;
        this.jsonValueMapper = jsonValueMapper;
    }

    @Override
    public void save(Plan plan) {
        planJpaRepository.save(toEntity(plan));
    }

    @Override
    public Optional<Plan> findByAgentId(String agentId) {
        return planJpaRepository.findById(agentId).map(this::toDomain);
    }

    @Override
    public void delete(String agentId) {
        planJpaRepository.deleteById(agentId);
    }

    private PlanDO toEntity(Plan plan) {
        PlanDO entity = new PlanDO();
        entity.setAgentId(plan.getAgentId());
        entity.setCurrentStepIndex(plan.getCurrentStepIndex());
        entity.setSteps(jsonValueMapper.write(plan.getSteps().stream()
                .map(step -> new PlanStepSnapshot(
                        step.getIndex(),
                        step.getThought(),
                        step.getAction(),
                        step.getActionInput(),
                        step.getObservation(),
                        step.getStatus().name()))
                .toList()));
        return entity;
    }

    private Plan toDomain(PlanDO entity) {
        List<PlanStepSnapshot> snapshots = jsonValueMapper.read(
                entity.getSteps(), new TypeReference<List<PlanStepSnapshot>>() {}, new ArrayList<>());
        List<PlanStep> steps = snapshots.stream()
                .map(step -> PlanStep.builder()
                        .index(step.getIndex())
                        .thought(step.getThought())
                        .action(step.getAction())
                        .actionInput(step.getActionInput())
                        .observation(step.getObservation())
                        .status(StepStatus.valueOf(step.getStatus()))
                        .build())
                .toList();
        return Plan.restore(entity.getAgentId(), steps, entity.getCurrentStepIndex());
    }
}
