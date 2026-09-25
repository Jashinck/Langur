package org.skylark.langur.config;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.rsi.MemoryDistiller;
import org.skylark.langur.domain.harness.rsi.ReplayEngine;
import org.skylark.langur.domain.harness.rsi.TemplateDistillationExtractor;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryTrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.RsiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * R0/R2 验收 - {@link RsiConfiguration} 装配离线单测（直接调用 Bean 工厂方法 + 注解断言）。
 * <p>验收点：默认关闭（{@code @ConditionalOnProperty} 无 matchIfMissing，enabled=false 时整个配置类 back-off
 * 不产 Bean，P10/P11 RSI 暂停态）；开启后装配 R0 回放底座（{@link ReplayEngine} + 缺省内存 {@link TrajectoryRepository}）
 * 与 R2 蒸馏（{@link MemoryDistiller}，缺省模板抽取器 + 缺省分开关 false）。</p>
 */
class RsiConfigurationTest {

    private final RsiConfiguration configuration = new RsiConfiguration();

    @Test
    void shouldDefaultToOffViaConditionalPropertyWithoutMatchIfMissing() {
        ConditionalOnProperty condition = RsiConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, "RSI 必须由开关控制（P11 暂停态红线）");
        assertEquals("langur.rsi.enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing(), "缺省不匹配 → 默认关闭，行为与既有版本一致");

        assertFalse(new RsiProperties().isEnabled(), "属性默认值同为 false");
        assertFalse(new RsiProperties().getReflection().isEnabled(), "R1 反思默认关闭");
        assertFalse(new RsiProperties().getDistillation().isEnabled(), "R2 蒸馏默认关闭");
    }

    @Test
    void shouldAssembleReplayEngineAndInMemoryTrajectoryRepositoryWhenEnabled() {
        assertInstanceOf(ReplayEngine.class, configuration.replayEngine());

        TrajectoryRepository repository = configuration.trajectoryRepository();
        assertInstanceOf(InMemoryTrajectoryRepository.class, repository, "缺省内存轨迹仓库（可被覆盖，P5）");
        assertNotNull(repository.listTaskIds());
    }

    @Test
    void shouldAssembleTemplateDistillationExtractorByDefault() {
        RsiProperties properties = new RsiProperties();
        properties.setEnabled(true);

        // llmEnabled=false → 不触碰 ObjectProvider，可安全传 null
        assertInstanceOf(TemplateDistillationExtractor.class,
                configuration.distillationExtractor(properties, null), "缺省模板抽取器（离线零网络，P10）");
    }

    @Test
    void shouldAssembleMemoryDistillerWithConfiguredThresholds() {
        RsiProperties properties = new RsiProperties();
        properties.setEnabled(true);
        properties.getDistillation().setMinConfidence(0.8);
        properties.getDistillation().setDedupThreshold(0.9);
        properties.getDistillation().setNamespace("custom-ns");

        MemoryDistiller distiller = configuration.memoryDistiller(new TemplateDistillationExtractor(), properties);
        assertNotNull(distiller);
        // 蒸馏器装配成功即可；阈值经构造注入（纯领域，由 MemoryDistillerTest 详测）
    }

    @Test
    void shouldDefaultDistillationPropertiesToConservativeValues() {
        RsiProperties.Distillation d = new RsiProperties().getDistillation();
        assertEquals(0.85, d.getMinConfidence(), 1e-9);
        assertEquals(0.95, d.getDedupThreshold(), 1e-9);
        assertEquals("rsi-distilled", d.getNamespace());
        assertFalse(d.isLlmEnabled());
    }
}
