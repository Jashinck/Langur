package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "langur.llm.providers.qwen", name = "enabled", havingValue = "true")
public class QwenLLMAdapter extends AbstractOpenAICompatibleLLMAdapter {

    public QwenLLMAdapter(LlmProperties llmProperties, ObjectMapper objectMapper) {
        super(llmProperties, objectMapper, "qwen");
    }

    @Override
    public String getProviderName() {
        return "qwen";
    }

    @Override
    public boolean supportsModel(String model) {
        return model != null && model.startsWith("qwen-");
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }
}
