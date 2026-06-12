package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "langur.llm.providers.deepseek", name = "enabled", havingValue = "true")
public class DeepSeekLLMAdapter extends AbstractOpenAICompatibleLLMAdapter {

    public DeepSeekLLMAdapter(LlmProperties llmProperties, ObjectMapper objectMapper) {
        super(llmProperties, objectMapper, "deepseek");
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public boolean supportsModel(String model) {
        return model != null && model.startsWith("deepseek-");
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.deepseek.com/v1";
    }
}
