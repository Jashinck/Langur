package org.skylark.langur.infrastructure.llm;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LLMRouterTest {

    @Test
    void shouldRouteByModelAliasAndProviderPrefix() {
        RecordingPort openai = new RecordingPort("openai", model -> model.startsWith("gpt-"));
        RecordingPort deepseek = new RecordingPort("deepseek", model -> model.startsWith("deepseek-"));
        LlmProperties properties = new LlmProperties();
        properties.setDefaultProvider("openai");
        properties.getRoutingRules().put("fast", "deepseek-chat");
        properties.getProviders().put("openai", provider("gpt-4o"));
        properties.getProviders().put("deepseek", provider("deepseek-chat"));

        LLMRouter router = new LLMRouter(List.of(openai, deepseek), properties);

        router.decide("system", "fast", List.of(), List.of());

        assertEquals("deepseek-chat", deepseek.lastModel);
    }

    @Test
    void shouldFallbackToDefaultProviderModelWhenModelMissing() {
        RecordingPort openai = new RecordingPort("openai", model -> model.startsWith("gpt-"));
        LlmProperties properties = new LlmProperties();
        properties.setDefaultProvider("openai");
        properties.getProviders().put("openai", provider("gpt-4o"));

        LLMRouter router = new LLMRouter(List.of(openai), properties);

        router.complete("system", null, "hello");

        assertEquals("gpt-4o", openai.lastModel);
    }

    private static LlmProperties.ProviderProperties provider(String model) {
        LlmProperties.ProviderProperties properties = new LlmProperties.ProviderProperties();
        properties.setModel(model);
        return properties;
    }

    private static class RecordingPort implements ModelRoutableLLMPort {
        private final String providerName;
        private final java.util.function.Predicate<String> predicate;
        private String lastModel;

        private RecordingPort(String providerName, java.util.function.Predicate<String> predicate) {
            this.providerName = providerName;
            this.predicate = predicate;
        }

        @Override
        public String getProviderName() {
            return providerName;
        }

        @Override
        public boolean supportsModel(String model) {
            return predicate.test(model);
        }

        @Override
        public LLMDecision decide(String systemPrompt, String model, List<Map<String, String>> conversationHistory, List<org.skylark.langur.domain.model.tool.Tool> availableTools) {
            this.lastModel = model;
            return LLMDecision.finalAnswer("ok");
        }

        @Override
        public String complete(String systemPrompt, String model, String userMessage) {
            this.lastModel = model;
            return "ok";
        }
    }
}
