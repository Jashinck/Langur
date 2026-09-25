package org.skylark.langur.infrastructure.llm;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 模型路由前缀匹配（H13.1/H13.3）- 替代各适配器硬编码的 supportsModel 前缀。
 * <p>匹配顺序：显式配置的 {@code model-prefixes} → 由 {@code provider.model} 派生前缀
 * （截取到首个 '-'，如 {@code gpt-4o} → {@code gpt-}）→ 全等匹配。</p>
 */
final class ModelPrefixes {

    private ModelPrefixes() {
    }

    static boolean matches(String model, List<String> configuredPrefixes, String providerModel) {
        if (StringUtils.isBlank(model)) {
            return false;
        }
        if (configuredPrefixes != null && !configuredPrefixes.isEmpty()) {
            return configuredPrefixes.stream()
                    .filter(StringUtils::isNotBlank)
                    .anyMatch(model::startsWith);
        }
        if (StringUtils.isBlank(providerModel)) {
            return false;
        }
        String derived = derivePrefix(providerModel);
        return derived.isEmpty() ? model.equals(providerModel) : model.startsWith(derived);
    }

    private static String derivePrefix(String providerModel) {
        int idx = providerModel.indexOf('-');
        return idx > 0 ? providerModel.substring(0, idx + 1) : "";
    }
}
