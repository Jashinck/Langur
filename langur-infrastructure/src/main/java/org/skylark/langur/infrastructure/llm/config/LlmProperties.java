package org.skylark.langur.infrastructure.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "langur.llm")
public class LlmProperties {

    private String defaultProvider = "openai";
    private Map<String, ProviderProperties> providers = new HashMap<>();
    private Map<String, String> routingRules = new HashMap<>();

    public ProviderProperties getProvider(String provider) {
        return providers.getOrDefault(provider, new ProviderProperties());
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
