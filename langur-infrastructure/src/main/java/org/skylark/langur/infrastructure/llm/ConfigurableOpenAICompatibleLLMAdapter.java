package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.List;

/**
 * 配置驱动的 OpenAI 兼容适配器（H13.1）- 由 {@code ModelProviderFactory} 按
 * {@code langur.llm.providers.<name>.type=openai-compatible} 实例化。
 * <p>新增 OpenAI 兼容厂商（GLM/Moonshot/百川/讯飞/阶跃…）= 纯 YAML，零 Java 代码（P5/P9）。</p>
 */
public class ConfigurableOpenAICompatibleLLMAdapter extends AbstractOpenAICompatibleLLMAdapter {

    public ConfigurableOpenAICompatibleLLMAdapter(String providerName,
                                                  LlmProperties.ProviderProperties providerProperties,
                                                  String defaultBaseUrl,
                                                  List<String> modelPrefixes,
                                                  ObjectMapper objectMapper) {
        super(providerName, providerProperties, defaultBaseUrl, modelPrefixes, objectMapper);
    }
}
