package org.skylark.langur.infrastructure.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "langur.llm")
public class LlmProperties {

    private String defaultProvider = "openai";
    private Map<String, ProviderProperties> providers = new HashMap<>();
    private Map<String, String> routingRules = new HashMap<>();
    /** T15 模型矩阵：角色名（ModelRole）→ 模型名。 */
    private Map<String, String> roleModels = new HashMap<>();
    /** T15 降级链：主模型名 → 备用模型名列表（主不可用时按序降级）。 */
    private Map<String, List<String>> fallbackChains = new HashMap<>();

    public ProviderProperties getProvider(String provider) {
        return providers.getOrDefault(provider, new ProviderProperties());
    }

    public List<String> fallbacksOf(String model) {
        return fallbackChains.getOrDefault(model, List.of());
    }

    @Getter
    @Setter
    public static class ProviderProperties {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private String model;
    }
}
