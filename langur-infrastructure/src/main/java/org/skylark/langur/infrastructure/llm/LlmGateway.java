package org.skylark.langur.infrastructure.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 统一 LLM 网关（§6）- 所有模型调用的统一入口，支持同步/流式，内置角色路由 + 主备降级。
 * <p>角色→模型映射配置化（{@code langur.llm.role-models}）；主模型不可用时按
 * {@code langur.llm.fallback-chains} 顺序自动降级到备用模型，全部失败方抛出。</p>
 * <p>H13.4：适配器传输失败改抛 {@link ModelProviderException}（不再吞成 "Error: ..." 字符串），
 * {@link #withFallback} 由此真正逐级降级；每次降级触发 {@code llm_fallback_count} 计数（接 H5）。</p>
 */
@Slf4j
@Component
public class LlmGateway {

    private final LLMPort llmPort;
    private final LlmProperties properties;
    private final MeterRegistry meterRegistry;

    /** H13.7 每 provider 独立熔断器（懒建，key=provider 名）。 */
    private final ConcurrentMap<String, ProviderCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public LlmGateway(LLMPort llmPort, LlmProperties properties) {
        this(llmPort, properties, (MeterRegistry) null);
    }

    @Autowired
    public LlmGateway(LLMPort llmPort, LlmProperties properties,
                      ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.llmPort = llmPort;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public LlmGateway(LLMPort llmPort, LlmProperties properties, MeterRegistry meterRegistry) {
        this.llmPort = llmPort;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
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

    /** 补全并回传真实 Token 计量（H13.5）：降级链语义与 {@link #complete} 一致。 */
    public LLMPort.CompletionResult completeWithUsage(ModelRole role, String systemPrompt, String userMessage) {
        return withFallback(role, model -> llmPort.completeWithUsage(systemPrompt, model, userMessage));
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
     * <p>H13.7：每 provider 独立熔断——跳闸冷却期内的 provider 快速跳过（不再反复触发慢超时），
     * 成功复位、失败重新跳闸；熔断配置经 {@code langur.llm.circuit-breaker.*}（缺省启用）。</p>
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
            String provider = properties.providerOf(model);
            ProviderCircuitBreaker breaker = breakerFor(provider);
            if (breaker != null && breaker.isOpen()) {
                recordCircuitSkip(provider, model);
                log.warn("[LLM] provider [{}] circuit open, fast-skipping model [{}] for role [{}]",
                        provider, model, role);
                continue;
            }
            try {
                T result = invocation.apply(model);
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                if (breaker != null) {
                    breaker.recordFailure();
                    if (breaker.isOpen()) {
                        recordCircuitOpen(provider);
                    }
                }
                recordFallback(role, model, e);
                log.warn("[LLM] model [{}] failed for role [{}], trying next in fallback chain: {}",
                        model, role, e.getMessage());
            }
        }
        if (lastError == null) {
            throw new IllegalStateException(
                    "All providers circuit-open for role [" + role + "]: " + chain);
        }
        throw new IllegalStateException(
                "All models in fallback chain exhausted for role [" + role + "]: " + chain, lastError);
    }

    /** H13.7：按 provider 懒建熔断器；熔断关闭时返回 null（调用方走既有降级路径）。 */
    private ProviderCircuitBreaker breakerFor(String provider) {
        LlmProperties.CircuitBreaker config = properties.getCircuitBreaker();
        if (config == null || !config.isEnabled()) {
            return null;
        }
        return breakers.computeIfAbsent(provider, p -> new ProviderCircuitBreaker(
                p, config.getThreshold(), config.getCooldownSeconds() * 1000L));
    }

    /** H13.7：熔断跳闸事件计数（provider 标签，接 H5）。 */
    private void recordCircuitOpen(String provider) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder("langur.harness.llm_provider_circuit_open_count")
                    .tag("provider", provider)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("[LLM] circuit-open metric record failed, dropped", e);
        }
    }

    /** H13.7：熔断快速跳过计数（provider/model 标签，接 H5）。 */
    private void recordCircuitSkip(String provider, String model) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder("langur.harness.llm_provider_circuit_skip_count")
                    .tag("provider", provider)
                    .tag("model", model)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("[LLM] circuit-skip metric record failed, dropped", e);
        }
    }

    /** 降级触发计数（H13.4，接 H5）：修复前适配器吞异常导致该指标恒 0。 */
    private void recordFallback(ModelRole role, String failedModel, RuntimeException error) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder("langur.harness.llm_fallback_count")
                    .tag("role", role != null ? role.name() : "UNKNOWN")
                    .tag("model", failedModel)
                    .tag("error", error.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("[LLM] fallback metric record failed, dropped", e);
        }
    }
}
