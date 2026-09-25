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
import java.util.function.Consumer;

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

    /** H13.5：路由后透传真实 usage，避免默认实现降级为空计量。 */
    @Override
    public CompletionResult completeWithUsage(String systemPrompt, String model, String userMessage) {
        String resolvedModel = resolveModel(model);
        return route(resolvedModel).completeWithUsage(systemPrompt, resolvedModel, userMessage);
    }

    @Override
    public void streamComplete(String systemPrompt, String model, String userMessage,
                               Consumer<String> tokenConsumer) {
        String resolvedModel = resolveModel(model);
        route(resolvedModel).streamComplete(systemPrompt, resolvedModel, userMessage, tokenConsumer);
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
