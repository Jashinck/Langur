package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13.1 验收 - 配置驱动 Provider 类型工厂：按 type 实例化适配器模板；
 * 新增 OpenAI 兼容厂商纯配置零代码；既有 5 家（OpenAI/Claude/Gemini/Qwen/DeepSeek）行为回归不变。
 */
class ModelProviderFactoryTest {

    private final ModelProviderFactory factory = new ModelProviderFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static LlmProperties.ProviderProperties provider(Boolean enabled, String type,
                                                             String baseUrl, String model,
                                                             List<String> prefixes) {
        LlmProperties.ProviderProperties props = new LlmProperties.ProviderProperties();
        props.setEnabled(enabled);
        props.setType(type);
        props.setBaseUrl(baseUrl);
        props.setModel(model);
        if (prefixes != null) {
            props.setModelPrefixes(prefixes);
        }
        return props;
    }

    @Test
    void shouldRegisterBuiltinOpenAiWhenNotConfigured() {
        List<ModelRoutableLLMPort> adapters = factory.create(new LlmProperties(), objectMapper);

        assertEquals(1, adapters.size());
        assertEquals("openai", adapters.get(0).getProviderName());
        assertTrue(adapters.get(0).supportsModel("gpt-4o"), "内置 openai 默认前缀 gpt- 回归不变");
    }

    @Test
    void shouldKeepOpenAiEnabledWhenEnabledFlagMissing() {
        // matchIfMissing 语义：openai 条目存在但未显式配置 enabled 时仍注册
        LlmProperties properties = new LlmProperties();
        properties.getProviders().put("openai", provider(null, null, null, "gpt-4o", null));

        List<ModelRoutableLLMPort> adapters = factory.create(properties, objectMapper);

        assertEquals(List.of("openai"), adapters.stream().map(ModelRoutableLLMPort::getProviderName).toList());
    }

    @Test
    void shouldNotRegisterOpenAiWhenExplicitlyDisabled() {
        LlmProperties properties = new LlmProperties();
        properties.getProviders().put("openai", provider(false, null, null, "gpt-4o", null));

        List<ModelRoutableLLMPort> adapters = factory.create(properties, objectMapper);

        assertTrue(adapters.stream().noneMatch(a -> a.getProviderName().equals("openai")));
    }

    @Test
    void shouldInstantiateAdapterTemplatesByConfiguredType() {
        LlmProperties properties = new LlmProperties();
        properties.getProviders().put("claude", provider(true, "anthropic", null, "claude-3-5-sonnet", null));
        properties.getProviders().put("gemini", provider(true, "gemini", null, "gemini-1.5-pro", null));
        properties.getProviders().put("qwen", provider(true, null, null, "qwen-max", null));

        List<ModelRoutableLLMPort> adapters = factory.create(properties, objectMapper);

        ModelRoutableLLMPort claude = byName(adapters, "claude");
        ModelRoutableLLMPort gemini = byName(adapters, "gemini");
        ModelRoutableLLMPort qwen = byName(adapters, "qwen");
        assertInstanceOf(ClaudeLLMAdapter.class, claude);
        assertInstanceOf(GeminiLLMAdapter.class, gemini);
        // type 缺省 = openai-compatible
        assertInstanceOf(ConfigurableOpenAICompatibleLLMAdapter.class, qwen);
        assertTrue(claude.supportsModel("claude-3-5-sonnet"));
        assertFalse(claude.supportsModel("gpt-4o"));
        assertTrue(gemini.supportsModel("gemini-1.5-pro"));
        assertTrue(qwen.supportsModel("qwen-max"));
        assertFalse(qwen.supportsModel("glm-4-plus"));
    }

    @Test
    void shouldRegisterNewOpenAiCompatibleVendorByPureConfiguration() {
        // P5/P9 验收：新增厂商（moonshot）零 Java 代码，仅配置即被识别路由
        LlmProperties properties = new LlmProperties();
        properties.getProviders().put("moonshot", provider(true, "openai-compatible",
                "https://api.moonshot.cn/v1", "moonshot-v1-8k", List.of("moonshot-")));

        List<ModelRoutableLLMPort> adapters = factory.create(properties, objectMapper);

        ModelRoutableLLMPort moonshot = byName(adapters, "moonshot");
        assertInstanceOf(ConfigurableOpenAICompatibleLLMAdapter.class, moonshot);
        assertTrue(moonshot.supportsModel("moonshot-v1-8k"));
        assertFalse(moonshot.supportsModel("gpt-4o"));
    }

    @Test
    void shouldPreserveExistingFiveVendorRoutingBehavior() {
        LlmProperties properties = new LlmProperties();
        properties.getProviders().put("openai", provider(true, null, null, "gpt-4o", null));
        properties.getProviders().put("qwen", provider(true, null, null, "qwen-max", null));
        properties.getProviders().put("deepseek", provider(true, null, null, "deepseek-chat", null));
        properties.getProviders().put("claude", provider(true, null, null, "claude-3-5-sonnet", null));
        properties.getProviders().put("gemini", provider(true, null, null, "gemini-1.5-pro", null));

        List<ModelRoutableLLMPort> adapters = factory.create(properties, objectMapper);

        assertEquals(List.of("openai", "qwen", "deepseek", "claude", "gemini"),
                adapters.stream().map(ModelRoutableLLMPort::getProviderName).toList(),
                "保持 YAML 声明顺序，路由确定性");
        assertTrue(byName(adapters, "openai").supportsModel("o1-preview"));
        assertTrue(byName(adapters, "openai").supportsModel("o3-mini"));
        assertTrue(byName(adapters, "deepseek").supportsModel("deepseek-chat"));
        assertTrue(byName(adapters, "claude").supportsModel("claude-3-5-sonnet"));
        assertTrue(byName(adapters, "gemini").supportsModel("gemini-1.5-pro"));
    }

    @Test
    void shouldSkipDisabledProviders() {
        LlmProperties properties = new LlmProperties();
        properties.getProviders().put("openai", provider(true, null, null, "gpt-4o", null));
        properties.getProviders().put("deepseek", provider(false, null, null, "deepseek-chat", null));
        properties.getProviders().put("qwen", provider(null, null, null, "qwen-max", null));

        List<ModelRoutableLLMPort> adapters = factory.create(properties, objectMapper);

        assertEquals(List.of("openai"), adapters.stream().map(ModelRoutableLLMPort::getProviderName).toList());
    }

    @Test
    void shouldRejectUnknownTypeAndMissingBaseUrl() {
        LlmProperties badType = new LlmProperties();
        badType.getProviders().put("openai", provider(false, null, null, null, null));
        badType.getProviders().put("x", provider(true, "bogus", "https://x.example/v1", "x-1", null));
        assertThrows(IllegalArgumentException.class, () -> factory.create(badType, objectMapper));

        LlmProperties noBaseUrl = new LlmProperties();
        noBaseUrl.getProviders().put("openai", provider(false, null, null, null, null));
        noBaseUrl.getProviders().put("y", provider(true, "openai-compatible", null, "y-1", null));
        assertThrows(IllegalArgumentException.class, () -> factory.create(noBaseUrl, objectMapper));
    }

    private static ModelRoutableLLMPort byName(List<ModelRoutableLLMPort> adapters, String name) {
        return adapters.stream()
                .filter(a -> a.getProviderName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("adapter not found: " + name));
    }
}
