package org.skylark.langur.infrastructure.spi;

import org.skylark.langur.common.spi.BusinessExecutorSPI;
import org.skylark.langur.common.spi.ContextEnricherSPI;
import org.skylark.langur.common.spi.DecisionEngineSPI;
import org.skylark.langur.common.spi.OutputPostProcessorSPI;
import org.skylark.langur.common.spi.PromptTemplateSPI;
import org.skylark.langur.common.spi.SecurityPolicySPI;
import org.skylark.langur.common.spi.ToolProviderSPI;
import org.skylark.langur.domain.harness.spi.BizCodeRouter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link BizCodeRouter} 默认实现 - Spring 聚合全部 SPI Bean，按 bizCode 建索引。
 * <p>匹配策略：精确 bizCode 命中优先；未命中回退 {@code default} 业务域；仍无则空。
 * 上下文增强器/输出后处理器为全局链，按 {@code getOrder()} 升序。</p>
 */
@Component
public class DefaultBizCodeRouter implements BizCodeRouter {

    private final Map<String, BusinessExecutorSPI> executors;
    private final Map<String, PromptTemplateSPI> promptTemplates;
    private final Map<String, ToolProviderSPI> toolProviders;
    private final Map<String, SecurityPolicySPI> securityPolicies;
    private final Map<String, DecisionEngineSPI> decisionEngines;
    private final List<ContextEnricherSPI> contextEnrichers;
    private final List<OutputPostProcessorSPI> outputPostProcessors;

    public DefaultBizCodeRouter(List<BusinessExecutorSPI> executors,
                                List<PromptTemplateSPI> promptTemplates,
                                List<ToolProviderSPI> toolProviders,
                                List<SecurityPolicySPI> securityPolicies,
                                List<DecisionEngineSPI> decisionEngines,
                                List<ContextEnricherSPI> contextEnrichers,
                                List<OutputPostProcessorSPI> outputPostProcessors) {
        this.executors = index(executors, BusinessExecutorSPI::getBizCode);
        this.promptTemplates = index(promptTemplates, PromptTemplateSPI::getBizCode);
        this.toolProviders = index(toolProviders, ToolProviderSPI::getBizCode);
        this.securityPolicies = index(securityPolicies, SecurityPolicySPI::getBizCode);
        this.decisionEngines = index(decisionEngines, DecisionEngineSPI::getBizCode);
        this.contextEnrichers = contextEnrichers.stream()
                .sorted(Comparator.comparingInt(ContextEnricherSPI::getOrder))
                .toList();
        this.outputPostProcessors = outputPostProcessors.stream()
                .sorted(Comparator.comparingInt(OutputPostProcessorSPI::getOrder))
                .toList();
    }

    @Override
    public Optional<BusinessExecutorSPI> executor(String bizCode) {
        return resolve(executors, bizCode);
    }

    @Override
    public Optional<PromptTemplateSPI> promptTemplate(String bizCode) {
        return resolve(promptTemplates, bizCode);
    }

    @Override
    public Optional<ToolProviderSPI> toolProvider(String bizCode) {
        return resolve(toolProviders, bizCode);
    }

    @Override
    public Optional<SecurityPolicySPI> securityPolicy(String bizCode) {
        return resolve(securityPolicies, bizCode);
    }

    @Override
    public Optional<DecisionEngineSPI> decisionEngine(String bizCode) {
        return resolve(decisionEngines, bizCode);
    }

    @Override
    public List<ContextEnricherSPI> contextEnrichers() {
        return contextEnrichers;
    }

    @Override
    public List<OutputPostProcessorSPI> outputPostProcessors() {
        return outputPostProcessors;
    }

    private static <T> Map<String, T> index(List<T> beans, Function<T, String> keyFn) {
        Map<String, T> map = new HashMap<>();
        if (beans != null) {
            for (T bean : beans) {
                String key = keyFn.apply(bean);
                if (key != null) {
                    map.putIfAbsent(key, bean);
                }
            }
        }
        return map;
    }

    private static <T> Optional<T> resolve(Map<String, T> map, String bizCode) {
        if (bizCode != null) {
            T exact = map.get(bizCode);
            if (exact != null) {
                return Optional.of(exact);
            }
        }
        return Optional.ofNullable(map.get(DEFAULT_BIZ_CODE));
    }
}
