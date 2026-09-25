package org.skylark.langur.config;

import org.skylark.langur.domain.harness.execution.DefaultLayerRouter;
import org.skylark.langur.domain.harness.execution.LayerRouter;
import org.skylark.langur.domain.harness.workflow.WorkflowRepository;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.domain.service.PlanningDomainService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 领域服务装配。
 * <p>Domain 层保持零 Spring 依赖（P1），纯 POJO 领域服务在此统一注册为容器 Bean，
 * 依赖由基础设施层实现注入，实现依赖倒置。</p>
 */
@Configuration
public class DomainServiceConfiguration {

    @Bean
    public AgentDomainService agentDomainService(LLMPort llmPort) {
        return new AgentDomainService(llmPort);
    }

    @Bean
    public PlanningDomainService planningDomainService() {
        return new PlanningDomainService();
    }

    @Bean
    public LayerRouter layerRouter(WorkflowRepository workflowRepository) {
        return new DefaultLayerRouter(workflowRepository);
    }
}
