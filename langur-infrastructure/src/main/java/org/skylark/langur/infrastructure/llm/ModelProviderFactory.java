package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置驱动的 Provider 类型工厂（H13.1）- 遍历 {@code langur.llm.providers.*}，按 {@code type} 实例化适配器。
 * <p>注册范式：从"一家一个 @ConditionalOnProperty @Component"改为"一个工厂读配置动态建 Bean"，
 * 产出 {@code List<ModelRoutableLLMPort>} 供 {@code LLMRouter} 注入。既有适配器逻辑全部复用。</p>
 * <p>新增 OpenAI 兼容厂商 = 纯 YAML（{@code type: openai-compatible}），零 Java 代码（P5/P9）。
 * 内置 {@code openai} 保留 matchIfMissing 语义：未显式配置或未显式置 false 时默认注册。</p>
 */
@Slf4j
public class ModelProviderFactory {

    public static final String TYPE_OPENAI_COMPATIBLE = "openai-compatible";
    public static final String TYPE_ANTHROPIC = "anthropic";
    public static final String TYPE_GEMINI = "gemini";
    public static final String BUILTIN_DEFAULT_PROVIDER = "openai";

    /** 知名厂商内置默认（type/默认 base-url/默认路由前缀），配置可覆盖。 */
    private static final Map<String, ProviderTemplate> BUILTIN_TEMPLATES = builtinTemplates();

    private record ProviderTemplate(String type, String baseUrl, List<String> modelPrefixes) {
    }

    private static Map<String, ProviderTemplate> builtinTemplates() {
        Map<String, ProviderTemplate> templates = new LinkedHashMap<>();
        templates.put("openai", new ProviderTemplate(TYPE_OPENAI_COMPATIBLE,
                "https://api.openai.com/v1", List.of("gpt-", "o1-", "o3-")));
        templates.put("qwen", new ProviderTemplate(TYPE_OPENAI_COMPATIBLE,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", List.of("qwen-")));
        templates.put("deepseek", new ProviderTemplate(TYPE_OPENAI_COMPATIBLE,
                "https://api.deepseek.com/v1", List.of("deepseek-")));
        templates.put("glm", new ProviderTemplate(TYPE_OPENAI_COMPATIBLE,
                "https://open.bigmodel.cn/api/paas/v4", List.of("glm-", "chatglm-")));
        templates.put("claude", new ProviderTemplate(TYPE_ANTHROPIC,
                "https://api.anthropic.com", List.of("claude-")));
        templates.put("gemini", new ProviderTemplate(TYPE_GEMINI,
                "https://generativelanguage.googleapis.com", List.of("gemini-")));
        return Map.copyOf(templates);
    }

    /**
     * 按配置产出全部启用的 provider 适配器；顺序即 YAML 声明顺序（路由首个前缀命中优先）。
     */
    public List<ModelRoutableLLMPort> create(LlmProperties properties, ObjectMapper objectMapper) {
        List<ModelRoutableLLMPort> adapters = new ArrayList<>();
        properties.getProviders().forEach((name, providerProperties) -> {
            boolean enabled = providerProperties.enabledOrDefault(BUILTIN_DEFAULT_PROVIDER.equals(name));
            if (!enabled) {
                return;
            }
            adapters.add(createAdapter(name, providerProperties, objectMapper));
        });
        if (!properties.getProviders().containsKey(BUILTIN_DEFAULT_PROVIDER)) {
            // matchIfMissing 语义：完全未配置 openai 时仍注册内置默认，保证 default-provider 可用
            adapters.add(createAdapter(BUILTIN_DEFAULT_PROVIDER, new LlmProperties.ProviderProperties(), objectMapper));
        }
        log.info("[LLM] provider factory created {} adapter(s): {}", adapters.size(),
                adapters.stream().map(ModelRoutableLLMPort::getProviderName).toList());
        return adapters;
    }

    private ModelRoutableLLMPort createAdapter(String name,
                                               LlmProperties.ProviderProperties providerProperties,
                                               ObjectMapper objectMapper) {
        ProviderTemplate template = BUILTIN_TEMPLATES.get(name);
        String type = StringUtils.defaultIfBlank(providerProperties.getType(),
                template != null ? template.type() : TYPE_OPENAI_COMPATIBLE);
        String defaultBaseUrl = template != null ? template.baseUrl() : null;
        List<String> prefixes = providerProperties.getModelPrefixes() != null
                && !providerProperties.getModelPrefixes().isEmpty()
                ? providerProperties.getModelPrefixes()
                : (template != null ? template.modelPrefixes() : List.of());
        if (defaultBaseUrl == null && StringUtils.isBlank(providerProperties.getBaseUrl())) {
            throw new IllegalArgumentException(
                    "provider [" + name + "] requires base-url (no built-in default for unknown provider)");
        }
        return switch (type) {
            case TYPE_ANTHROPIC -> new ClaudeLLMAdapter(name, providerProperties, defaultBaseUrl, prefixes, objectMapper);
            case TYPE_GEMINI -> new GeminiLLMAdapter(name, providerProperties, defaultBaseUrl, prefixes, objectMapper);
            case TYPE_OPENAI_COMPATIBLE -> new ConfigurableOpenAICompatibleLLMAdapter(
                    name, providerProperties, defaultBaseUrl, prefixes, objectMapper);
            default -> throw new IllegalArgumentException(
                    "Unsupported provider type [" + type + "] for provider [" + name
                            + "]; expected one of: " + TYPE_OPENAI_COMPATIBLE + " | " + TYPE_ANTHROPIC + " | " + TYPE_GEMINI);
        };
    }
}
