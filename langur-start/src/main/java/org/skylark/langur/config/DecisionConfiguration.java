package org.skylark.langur.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.common.cache.CacheBackend;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.infrastructure.harness.decision.CachingDecisionPort;
import org.skylark.langur.infrastructure.harness.decision.DataResidencyDecisionPort;
import org.skylark.langur.infrastructure.harness.decision.DecisionProperties;
import org.skylark.langur.infrastructure.harness.decision.DecisionTrajectoryRecorder;
import org.skylark.langur.infrastructure.harness.decision.LocalDecisionAdapter;
import org.skylark.langur.infrastructure.harness.decision.LoggingDecisionTrajectoryRecorder;
import org.skylark.langur.infrastructure.harness.decision.RecordingDecisionPort;
import org.skylark.langur.infrastructure.harness.decision.RuleFallbackDecisionAdapter;
import org.skylark.langur.infrastructure.harness.decision.ThresholdRouter;
import org.skylark.langur.infrastructure.harness.decision.TypeSafeDecisionAdapter;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 决策平面装配（J3，start）。经 {@link ObjectProvider} 松耦合组装 v3.0 §5.2 装饰链：
 * <pre>
 * DecisionPort(@Primary) = RecordingDecisionPort        // 录制进快照 + 决策指标 + 审计（J2）
 *   └ CachingDecisionPort                               // H4 CacheBackend 按 state 哈希缓存（J3）
 *     └ ThresholdRouter                                 // 置信阈值分流 + fail-closed（J3）
 *       └ backend：TypeSafe | Local | DataResidency | RuleFallback（DD10 数据驻留）
 * </pre>
 * <p><b>默认关闭</b>（P10/P12①）：{@code langur.decision.enabled=false}（或缺省）时整个 {@code @Configuration}
 * 因 {@link ConditionalOnProperty} back-off，不产出任何 Bean，消费方 {@code ObjectProvider<DecisionPort>} 取空 →
 * 降级规则，行为与 v2.0 完全一致。开启时 {@code decisionPort} 标 {@link Primary}（{@link ThresholdRouter} 亦是
 * {@link DecisionPort} Bean，供消费方注入 {@code route()}），消除注入歧义。</p>
 * <p>{@code api-key-ref} 经 H7 {@link SecretResolver} 解析，绝不明文/落日志（P12⑥）；敏感命名空间强制 local、
 * 无 local 端点则 fail-closed 回退规则、绝不发往第三方（DD10/P12⑤）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "langur.decision.enabled", havingValue = "true")
public class DecisionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DecisionConfiguration.class);

    /** 缺省录制通道：R0 落地前的过渡留痕（可被同类型 Bean 覆盖，P5）。 */
    @Bean
    public DecisionTrajectoryRecorder decisionTrajectoryRecorder() {
        return new LoggingDecisionTrajectoryRecorder();
    }

    /** 阈值路由（包裹后端）；亦暴露为 Bean 供 J4–J10 消费方注入 {@link ThresholdRouter#route}。 */
    @Bean
    public ThresholdRouter thresholdRouter(DecisionProperties properties,
                                           ObjectMapper objectMapper,
                                           ObjectProvider<EvaluationService> evaluationServiceProvider,
                                           ObjectProvider<SecretResolver> secretResolverProvider) {
        DecisionPort backend = buildBackend(properties, objectMapper, secretResolverProvider.getIfAvailable());
        log.info("[Decision] assembling decision plane: backend={}, record={}, cache={}",
                properties.getBackend(), properties.isRecord(), properties.getCache().isEnabled());
        return new ThresholdRouter(backend, properties.getThresholds().toDomain(),
                evaluationServiceProvider.getIfAvailable());
    }

    /** 完整装饰链（@Primary DecisionPort）：录制 ⊃ 缓存 ⊃ 阈值路由。 */
    @Bean
    @Primary
    public DecisionPort decisionPort(ThresholdRouter thresholdRouter,
                                     DecisionProperties properties,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<CacheBackend> cacheBackendProvider,
                                     ObjectProvider<EvaluationService> evaluationServiceProvider,
                                     ObjectProvider<DecisionTrajectoryRecorder> recorderProvider) {
        DecisionPort caching = new CachingDecisionPort(
                thresholdRouter,
                cacheBackendProvider.getIfAvailable(),
                objectMapper,
                properties.getCache().isEnabled(),
                Duration.ofSeconds(properties.getCache().getTtlSeconds()));
        return new RecordingDecisionPort(
                caching,
                evaluationServiceProvider.getIfAvailable(),
                recorderProvider.getIfAvailable(),
                properties.isRecord());
    }

    /** 按 {@code backend} + 数据驻留构建链最内层后端；全程以 {@link RuleFallbackDecisionAdapter} 兜底（P10）。 */
    private DecisionPort buildBackend(DecisionProperties properties, ObjectMapper objectMapper, SecretResolver secrets) {
        RuleFallbackDecisionAdapter rule = new RuleFallbackDecisionAdapter();
        String backend = properties.getBackend() == null
                ? "typesafe"
                : properties.getBackend().toLowerCase(Locale.ROOT);
        switch (backend) {
            case "off":
                return rule;
            case "local":
                return new LocalDecisionAdapter(properties.getBaseUrl(), properties.getModel(),
                        properties.getTimeoutMillis(), objectMapper, rule);
            default: {
                String apiKey = resolveApiKey(properties, secrets);
                DecisionPort typesafe = new TypeSafeDecisionAdapter(properties.getBaseUrl(), properties.getModel(),
                        apiKey, properties.getTimeoutMillis(), objectMapper, rule);
                List<String> sensitive = properties.getDataResidency().getSensitiveNamespaces();
                if (sensitive == null || sensitive.isEmpty()) {
                    return typesafe;
                }
                String localUrl = properties.getDataResidency().getLocalBaseUrl();
                DecisionPort local = (localUrl != null && !localUrl.isBlank())
                        ? new LocalDecisionAdapter(localUrl, localModel(properties),
                                properties.getTimeoutMillis(), objectMapper, rule)
                        : null;
                return new DataResidencyDecisionPort(typesafe, local, rule, sensitive);
            }
        }
    }

    private static String localModel(DecisionProperties properties) {
        String localModel = properties.getDataResidency().getLocalModel();
        return (localModel != null && !localModel.isBlank()) ? localModel : properties.getModel();
    }

    /**
     * 经 {@link SecretResolver} 解析 {@code api-key-ref}（{@code env:/prop:/kms:}）；缺解析器/缺引用/解析为空
     * → 返回空串（无鉴权 → 后端拒绝 → 适配器委派规则兜底，即 fail-closed，绝不明文、绝不出网泄露）。
     */
    static String resolveApiKey(DecisionProperties properties, SecretResolver secrets) {
        String reference = properties.getApiKeyRef();
        if (secrets == null || reference == null || reference.isBlank()) {
            return "";
        }
        return secrets.resolve(reference)
                .filter(key -> key != null && !key.isBlank())
                .orElse("");
    }
}
