package org.skylark.langur.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.infrastructure.llm.ModelProviderFactory;
import org.skylark.langur.infrastructure.llm.ModelRoutableLLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 模型网关装配（H13.1）- 由 {@link ModelProviderFactory} 读 {@code langur.llm.providers.*} 配置
 * 动态产出 {@code List<ModelRoutableLLMPort>}，供 {@code LLMRouter}（@Primary LLMPort）构造注入。
 * <p>取代既有"每厂一个 @ConditionalOnProperty Bean"的注册方式；无独立元素 Bean 时，
 * Spring 对该 List 类型依赖直接注入本 @Bean 产出的列表。</p>
 */
@Configuration
public class ModelProviderConfiguration {

    @Bean
    public ModelProviderFactory modelProviderFactory() {
        return new ModelProviderFactory();
    }

    @Bean
    public List<ModelRoutableLLMPort> modelRoutableLLMPorts(ModelProviderFactory modelProviderFactory,
                                                            LlmProperties llmProperties,
                                                            ObjectMapper objectMapper) {
        return modelProviderFactory.create(llmProperties, objectMapper);
    }
}
