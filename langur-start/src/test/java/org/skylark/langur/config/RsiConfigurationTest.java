package org.skylark.langur.config;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.rsi.MemoryDistiller;
import org.skylark.langur.domain.harness.rsi.ReplayEngine;
import org.skylark.langur.domain.harness.rsi.RsiProposalRepository;
import org.skylark.langur.domain.harness.rsi.RsiSafetyPlane;
import org.skylark.langur.domain.harness.rsi.StrategyOptimizer;
import org.skylark.langur.domain.harness.rsi.TemplateDistillationExtractor;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryRsiProposalRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryTrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.RsiProperties;
import org.skylark.langur.infrastructure.harness.rsi.SkillSynthesisValidator;
import org.skylark.langur.infrastructure.harness.rsi.SkillSynthesizer;
import org.skylark.langur.infrastructure.harness.rsi.SynthesizedSkillRegistrar;
import org.skylark.langur.infrastructure.harness.rsi.ToolExtensionRegistrar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * R0/R2/R3/R-G 验收 - {@link RsiConfiguration} 装配离线单测（直接调用 Bean 工厂方法 + 注解断言）。
 * <p>验收点：默认关闭（{@code @ConditionalOnProperty} 无 matchIfMissing，enabled=false 时整个配置类 back-off
 * 不产 Bean，P10/P11 RSI 暂停态）；开启后装配 R0 回放底座、R2 蒸馏、R3 技能自合成、R-G 安全平面（均另行分开关）。</p>
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
        assertFalse(new RsiProperties().getSynthesis().isEnabled(), "R3 合成默认关闭");
        assertFalse(new RsiProperties().getGovernance().isEnabled(), "R-G 安全平面默认关闭");
        assertFalse(new RsiProperties().getOptimization().isEnabled(), "R4 策略自优化默认关闭");
        assertFalse(new RsiProperties().getExtension().isEnabled(), "R5 工具自扩展默认关闭");
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

    @Test
    void shouldAssembleR3SynthesisBeans() {
        assertInstanceOf(SkillSynthesizer.class, configuration.skillSynthesizer());
        assertInstanceOf(SkillSynthesisValidator.class, configuration.skillSynthesisValidator());
        assertInstanceOf(SynthesizedSkillRegistrar.class, configuration.synthesizedSkillRegistrar());
    }

    @Test
    void shouldDefaultSynthesisAllowlistToEmpty() {
        assertFalse(new RsiProperties().getSynthesis().isEnabled());
        assertNotNull(new RsiProperties().getSynthesis().getAllowedToolIds());
    }

    @Test
    void shouldAssembleRsiGovernanceBeans() {
        RsiProperties properties = new RsiProperties();
        properties.setEnabled(true);

        RsiProposalRepository repository = configuration.rsiProposalRepository();
        assertInstanceOf(InMemoryRsiProposalRepository.class, repository);
        assertInstanceOf(RsiSafetyPlane.class, configuration.rsiSafetyPlane(repository, properties));
    }

    @Test
    void shouldDefaultGovernancePropertiesToConservativeValues() {
        RsiProperties.Governance g = new RsiProperties().getGovernance();
        assertFalse(g.isEnabled());
        assertEquals(3, g.getMaxDepth());
        assertEquals(10, g.getMaxProposalsPerMinute());
        assertNotNull(g.getForbiddenTargetPrefixes());
        assertFalse(g.getForbiddenTargetPrefixes().isEmpty(), "权限隔离红线缺省应含 security.policy/validation.");
    }

    @Test
    void shouldAssembleStrategyOptimizer() {
        assertInstanceOf(StrategyOptimizer.class, configuration.strategyOptimizer(configuration.replayEngine()));
    }

    @Test
    void shouldAssembleToolExtensionRegistrar() {
        assertInstanceOf(ToolExtensionRegistrar.class, configuration.toolExtensionRegistrar());
    }
}
