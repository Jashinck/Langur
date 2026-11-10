package org.skylark.langur.config;

import org.skylark.langur.domain.harness.execution.DefaultLayerRouter;
import org.skylark.langur.domain.harness.execution.LayerRouter;
import org.skylark.langur.domain.harness.workflow.WorkflowRepository;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.domain.service.PlanningDomainService;
import org.skylark.langur.infrastructure.harness.decision.DecisionProperties;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * 分层路由器（H9 / J8）：确定性规则路由为基线；决策平面开启时经 {@link DecisionPort} 接缝（@Primary 装饰链）
     * 注入 advisory 路由——高置信覆盖规则、低置信/缺失回退规则，且对合规边界只收紧不放松（P10/P12②③）。
     * 决策平面关闭（缺省）时 provider 取空 → 不织入，路由行为与 v2.0 完全一致（P12①）。
     */
    @Bean
    public LayerRouter layerRouter(WorkflowRepository workflowRepository,
                                   ObjectProvider<DecisionPort> decisionPortProvider,
                                   ObjectProvider<DecisionProperties> decisionPropertiesProvider) {
        DefaultLayerRouter router = new DefaultLayerRouter(workflowRepository);
        DecisionPort decisionPort = decisionPortProvider.getIfAvailable();
        if (decisionPort != null) {
            DecisionProperties properties = decisionPropertiesProvider.getIfAvailable();
            router.attachDecisionPlane(decisionPort,
                    properties != null ? properties.getThresholds().toDomain() : null);
        }
        return router;
    }
}
