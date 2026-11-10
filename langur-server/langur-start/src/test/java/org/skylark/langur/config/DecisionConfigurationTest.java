package org.skylark.langur.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.infrastructure.harness.decision.CachingDecisionPort;
import org.skylark.langur.infrastructure.harness.decision.DataResidencyDecisionPort;
import org.skylark.langur.infrastructure.harness.decision.DecisionProperties;
import org.skylark.langur.infrastructure.harness.decision.LocalDecisionAdapter;
import org.skylark.langur.infrastructure.harness.decision.RecordingDecisionPort;
import org.skylark.langur.infrastructure.harness.decision.RuleFallbackDecisionAdapter;
import org.skylark.langur.infrastructure.harness.decision.ThresholdRouter;
import org.skylark.langur.infrastructure.harness.decision.TypeSafeDecisionAdapter;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J3 验收 - {@link DecisionConfiguration} 装饰链装配离线单测（直接调用 Bean 工厂方法 + getDelegate 遍历）。
 * <p>验收点：链序 Recording ⊃ Caching ⊃ Threshold ⊃ backend；backend 按配置选择（off/typesafe/local/数据驻留）；
 * 默认关闭（{@code @ConditionalOnProperty} 无 matchIfMissing，enabled=false 时整个配置类 back-off 不产 Bean，P12①）；
 * api-key-ref 经 SecretResolver 解析、解析失败降级空串（fail-closed 由适配器兜底，P12⑥）。</p>
 */
class DecisionConfigurationTest {

    private final DecisionConfiguration configuration = new DecisionConfiguration();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private ThresholdRouter buildRouter(DecisionProperties props) {
        return configuration.thresholdRouter(props, objectMapper, provider(null), provider(null));
    }

    @Test
    void shouldAssembleChainRecordingWrappingCachingWrappingThresholdWrappingBackend() {
        DecisionProperties props = new DecisionProperties();
        props.setBackend("off");

        ThresholdRouter router = buildRouter(props);
        DecisionPort chain = configuration.decisionPort(router, props, objectMapper,
                provider(null), provider(null), provider(configuration.decisionTrajectoryRecorder()));

        RecordingDecisionPort recording = assertInstanceOf(RecordingDecisionPort.class, chain);
        CachingDecisionPort caching = assertInstanceOf(CachingDecisionPort.class, recording.getDelegate());
        ThresholdRouter threshold = assertInstanceOf(ThresholdRouter.class, caching.getDelegate());
        assertInstanceOf(RuleFallbackDecisionAdapter.class, threshold.getDelegate(),
                "backend=off 时链最内层为规则兜底");
    }

    @Test
    void shouldSelectBackendByConfiguration() {
        DecisionProperties typesafeProps = new DecisionProperties();
        assertInstanceOf(TypeSafeDecisionAdapter.class, buildRouter(typesafeProps).getDelegate());

        DecisionProperties localProps = new DecisionProperties();
        localProps.setBackend("local");
        localProps.setBaseUrl("http://127.0.0.1:1/kev");
        assertInstanceOf(LocalDecisionAdapter.class, buildRouter(localProps).getDelegate());

        DecisionProperties residencyProps = new DecisionProperties();
        residencyProps.getDataResidency().setSensitiveNamespaces(List.of("legal-contracts"));
        residencyProps.getDataResidency().setLocalBaseUrl("http://127.0.0.1:1/kev");
        assertInstanceOf(DataResidencyDecisionPort.class, buildRouter(residencyProps).getDelegate(),
                "配置敏感命名空间时后端包数据驻留选择器（DD10）");
    }

    @Test
    void shouldDefaultToOffViaConditionalPropertyWithoutMatchIfMissing() {
        ConditionalOnProperty condition =
                DecisionConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, "决策平面必须由开关控制（P12①）");
        assertEquals("langur.decision.enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing(), "缺省不匹配 → 默认关闭，行为与 v2.0 一致");

        DecisionProperties props = new DecisionProperties();
        assertFalse(props.isEnabled(), "属性默认值同为 false");
        assertTrue(props.isRecord(), "record 默认开启（R0 前向兼容，P12④）");
    }

    @Test
    void shouldMarkDecisionPortPrimaryAndCarryConfiguredThresholds() throws NoSuchMethodException {
        Primary primary = DecisionConfiguration.class
                .getMethod("decisionPort", ThresholdRouter.class, DecisionProperties.class,
                        ObjectMapper.class, ObjectProvider.class, ObjectProvider.class,
                        ObjectProvider.class)
                .getAnnotation(Primary.class);
        assertNotNull(primary, "decisionPort Bean 标 @Primary 消除 DecisionPort 注入歧义");

        DecisionProperties props = new DecisionProperties();
        props.getThresholds().setRouting(0.6);
        assertEquals(new DecisionThresholds(0.6,
                        DecisionThresholds.DEFAULT_APPROVAL_AUTO,
                        DecisionThresholds.DEFAULT_ARTIFACT_ACCEPT,
                        DecisionThresholds.DEFAULT_COMPLETION),
                buildRouter(props).getThresholds(), "配置阈值经 toDomain() 注入路由器");
    }

    @Test
    void shouldResolveApiKeyViaSecretResolverAndDegradeToEmpty() {
        DecisionProperties props = new DecisionProperties();
        props.setApiKeyRef("env:JEV_API_KEY");

        AtomicReference<String> seen = new AtomicReference<>();
        assertEquals("resolved-key", DecisionConfiguration.resolveApiKey(props, reference -> {
            seen.set(reference);
            return Optional.of("resolved-key");
        }));
        assertEquals("env:JEV_API_KEY", seen.get(), "引用原样传递 resolver（env:/prop:/kms: 由 H7 组合解析）");

        assertEquals("", DecisionConfiguration.resolveApiKey(props, reference -> Optional.empty()),
                "解析为空 → 空串（后端拒绝 → 规则兜底 fail-closed，绝不明文）");
        assertEquals("", DecisionConfiguration.resolveApiKey(props, null), "缺 resolver → 空串");

        DecisionProperties blankRef = new DecisionProperties();
        blankRef.setApiKeyRef("  ");
        assertEquals("", DecisionConfiguration.resolveApiKey(blankRef, reference -> Optional.of("x")),
                "未配置引用 → 不触发解析");
    }

    @Test
    void shouldExposeLoggingTrajectoryRecorderByDefault() {
        assertNotNull(configuration.decisionTrajectoryRecorder(),
                "缺省录制通道在场（R0 落地前过渡留痕）");
    }
}
