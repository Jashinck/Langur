package org.skylark.langur.domain.service;

import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.StepStatus;

/**
 * 规划领域服务 - 负责Plan的创建与管理。
 * <p>纯领域服务（无 Spring 注解），由 start 层通过 @Configuration 装配，保证 Domain 层零外部依赖（P1）。</p>
 */
public class PlanningDomainService {

    public Plan createPlan(String agentId) {
        return new Plan(agentId);
    }

    public boolean isPlanComplete(Plan plan) {
        return plan.getSteps().stream()
                .allMatch(step -> step.getStatus() == StepStatus.COMPLETED
                        || step.getStatus() == StepStatus.FAILED
                        || step.getStatus() == StepStatus.SKIPPED);
    }
}