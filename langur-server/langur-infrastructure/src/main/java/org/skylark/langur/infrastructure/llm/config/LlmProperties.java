package org.skylark.langur.infrastructure.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "langur.llm")
public class LlmProperties {

    private String defaultProvider = "openai";
    /** 保持 YAML 声明顺序，使 LLMRouter 前缀路由顺序确定（H13.1）。 */
    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();
    private Map<String, String> routingRules = new HashMap<>();
    /** T15 模型矩阵：角色名（ModelRole）→ 模型名。 */
    private Map<String, String> roleModels = new HashMap<>();
    /** T15 降级链：主模型名 → 备用模型名列表（主不可用时按序降级）。 */
    private Map<String, List<String>> fallbackChains = new HashMap<>();
    /** H13.7 provider 熔断配置（{@code langur.llm.circuit-breaker.*}）。 */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    public ProviderProperties getProvider(String provider) {
        return providers.getOrDefault(provider, new ProviderProperties());
    }

    public List<String> fallbacksOf(String model) {
        return fallbackChains.getOrDefault(model, List.of());
    }

    /**
     * 模型 → provider 名解析（H13.7 熔断隔离键）：先精确匹配 provider 默认模型，再按
     * {@code model-prefixes}（H13.3）前缀匹配；未知模型回退以模型名自身作隔离键。
     */
    public String providerOf(String model) {
        if (model == null || model.isBlank()) {
            return defaultProvider;
        }
        for (Map.Entry<String, ProviderProperties> entry : providers.entrySet()) {
            if (model.equals(entry.getValue().getModel())) {
                return entry.getKey();
            }
        }
        for (Map.Entry<String, ProviderProperties> entry : providers.entrySet()) {
            for (String prefix : entry.getValue().getModelPrefixes()) {
                if (prefix != null && !prefix.isBlank() && model.startsWith(prefix)) {
                    return entry.getKey();
                }
            }
        }
        return model;
    }

    /**
     * 单个 Provider 配置（H13.1）- 由 {@code ModelProviderFactory} 按 {@link #type} 实例化适配器。
     * <p>{@link #enabled} 用包装类型区分"未显式配置"（{@code null}）与显式 false，
     * 以保留内置 {@code openai} 的 matchIfMissing 语义。</p>
     */
    @Getter
    @Setter
    public static class ProviderProperties {
        /** 是否启用；null 表示未显式配置（仅 openai 视为启用）。 */
        private Boolean enabled;
        /** 线格式类型：openai-compatible（缺省）| anthropic | gemini（H13.1）。 */
        private String type;
        private String baseUrl;
        /** 明文 Key（向后兼容，不推荐；优先 {@link #apiKeyRef}）。 */
        private String apiKey;
        /** 密钥引用：{@code env:/prop:/kms:}，经 SecretResolver 解析（H13.6）。 */
        private String apiKeyRef;
        private String model;
        /** 可配置路由匹配前缀；为空时回退内置默认前缀，再回退 {@link #model} 前缀（H13.3）。 */
        private List<String> modelPrefixes = new ArrayList<>();

        /** 显式启用判定：未配置时由调用方按 provider 名决定缺省语义。 */
        public boolean enabledOrDefault(boolean defaultValue) {
            return enabled == null ? defaultValue : enabled;
        }
    }

    /** H13.7 provider 熔断配置（{@code langur.llm.circuit-breaker.*}，缺省启用——仅连续失败后快速降级，无失败时零影响）。 */
    @Getter
    @Setter
    public static class CircuitBreaker {
        /** 是否启用 provider 熔断（缺省 true）。 */
        private boolean enabled = true;
        /** 连续失败达此阈值即跳闸（缺省 3）。 */
        private int threshold = 3;
        /** 跳闸冷却秒数（期内快速降级，冷却后放行试探，缺省 30）。 */
        private long cooldownSeconds = 30;
    }
}
