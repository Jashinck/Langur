package org.skylark.langur.infrastructure.llm;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 统一 LLM 网关（§6）- 所有模型调用的统一入口，支持同步/流式，内置角色路由 + 主备降级。
 * <p>角色→模型映射配置化（{@code langur.llm.role-models}）；主模型不可用时按
 * {@code langur.llm.fallback-chains} 顺序自动降级到备用模型，全部失败方抛出。</p>
 */
@Slf4j
@Component
public class LlmGateway {

    private final LLMPort llmPort;
    private final LlmProperties properties;

    public LlmGateway(LLMPort llmPort, LlmProperties properties) {
        this.llmPort = llmPort;
        this.properties = properties;
    }

    /** 解析角色对应模型：角色映射 → DEFAULT 映射 → 默认 provider 模型。 */
    public String resolveModel(ModelRole role) {
        String mapped = role != null ? properties.getRoleModels().get(role.name()) : null;
        if (StringUtils.isBlank(mapped)) {
            mapped = properties.getRoleModels().get("DEFAULT");
        }
        if (StringUtils.isBlank(mapped)) {
            mapped = properties.getProvider(properties.getDefaultProvider()).getModel();
        }
        return mapped;
    }

    public String complete(ModelRole role, String systemPrompt, String userMessage) {
        return withFallback(role, model -> llmPort.complete(systemPrompt, model, userMessage));
    }

    public LLMPort.LLMDecision decide(ModelRole role, String systemPrompt,
                                      List<Map<String, String>> conversationHistory,
                                      List<Tool> availableTools) {
        return withFallback(role, model -> llmPort.decide(systemPrompt, model, conversationHistory, availableTools));
    }

    public void streamComplete(ModelRole role, String systemPrompt, String userMessage, Consumer<String> tokenConsumer) {
        withFallback(role, model -> {
            llmPort.streamComplete(systemPrompt, model, userMessage, tokenConsumer);
            return null;
        });
    }

    /**
     * 主备降级执行：按 [主模型 + 备用链] 依次尝试，成功即返回，全部失败抛出最后一次异常。
     */
    private <T> T withFallback(ModelRole role, Function<String, T> invocation) {
        String primary = resolveModel(role);
        List<String> chain = new ArrayList<>();
        chain.add(primary);
        for (String backup : properties.fallbacksOf(primary)) {
            if (!chain.contains(backup)) {
                chain.add(backup);
            }
        }
        RuntimeException lastError = null;
        for (String model : chain) {
            try {
                return invocation.apply(model);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("[LLM] model [{}] failed for role [{}], trying next in fallback chain: {}",
                        model, role, e.getMessage());
            }
        }
        throw new IllegalStateException(
                "All models in fallback chain exhausted for role [" + role + "]: " + chain, lastError);
    }
}
