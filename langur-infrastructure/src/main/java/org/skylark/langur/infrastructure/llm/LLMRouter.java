package org.skylark.langur.infrastructure.llm;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Primary
@RequiredArgsConstructor
public class LLMRouter implements LLMPort {

    private final List<ModelRoutableLLMPort> providers;
    private final LlmProperties llmProperties;

    @Override
    public LLMDecision decide(String systemPrompt, String model,
                              List<Map<String, String>> conversationHistory,
                              List<Tool> availableTools) {
        String resolvedModel = resolveModel(model);
        return route(resolvedModel).decide(systemPrompt, resolvedModel, conversationHistory, availableTools);
    }

    @Override
    public String complete(String systemPrompt, String model, String userMessage) {
        String resolvedModel = resolveModel(model);
        return route(resolvedModel).complete(systemPrompt, resolvedModel, userMessage);
    }

    private ModelRoutableLLMPort route(String model) {
        return providers.stream()
                .filter(provider -> provider.supportsModel(model))
                .findFirst()
                .orElseGet(this::defaultProvider);
    }

    private ModelRoutableLLMPort defaultProvider() {
        String providerName = llmProperties.getDefaultProvider();
        return providers.stream()
                .filter(provider -> provider.getProviderName().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No LLM provider configured for default provider: " + providerName));
    }

    private String resolveModel(String model) {
        String routingTarget = llmProperties.getRoutingRules().get(model);
        if (StringUtils.isNotBlank(routingTarget)) {
            return routingTarget;
        }
        if (StringUtils.isNotBlank(model)) {
            return model;
        }
        return llmProperties.getProvider(llmProperties.getDefaultProvider()).getModel();
    }
}
