package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "langur.llm.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenAILLMAdapter extends AbstractOpenAICompatibleLLMAdapter {

    public OpenAILLMAdapter(LlmProperties llmProperties, ObjectMapper objectMapper) {
        super(llmProperties, objectMapper, "openai");
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public boolean supportsModel(String model) {
        return model != null && (model.startsWith("gpt-") || model.startsWith("o1-") || model.startsWith("o3-"));
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.openai.com/v1";
    }
}
