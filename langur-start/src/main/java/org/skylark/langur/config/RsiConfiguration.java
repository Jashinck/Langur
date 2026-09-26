package org.skylark.langur.config;

import org.skylark.langur.domain.harness.rsi.DistillationExtractor;
import org.skylark.langur.domain.harness.rsi.MemoryDistiller;
import org.skylark.langur.domain.harness.rsi.ReplayEngine;
import org.skylark.langur.domain.harness.rsi.RsiProposalRepository;
import org.skylark.langur.domain.harness.rsi.RsiSafetyPlane;
import org.skylark.langur.domain.harness.rsi.StrategyOptimizer;
import org.skylark.langur.domain.harness.rsi.TemplateDistillationExtractor;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryRsiProposalRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryTrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.LlmDistillationExtractor;
import org.skylark.langur.infrastructure.harness.rsi.RsiProperties;
import org.skylark.langur.infrastructure.harness.rsi.SkillSynthesisValidator;
import org.skylark.langur.infrastructure.harness.rsi.SkillSynthesizer;
import org.skylark.langur.infrastructure.harness.rsi.SynthesizedSkillRegistrar;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RSI 装配（R0/R2/R3/R-G，start）。<b>默认关闭</b>（P10/P11）：{@code langur.rsi.enabled=false}（或缺省）时整个
 * {@code @Configuration} 因 {@link ConditionalOnProperty} back-off，不产出任何 Bean，系统行为与既有版本完全一致。
 * <p>开启后装配 R0 回放底座、R2 记忆自蒸馏、R3 技能自合成、R-G 安全平面（均另行分开关）。产物都是 RSI 候选提案，
 * 须经 R0 回放 + R-G 三段式（验证 + 高危人审 + 灰度）方可生效（P11 红线：回放通过≠生效）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "langur.rsi.enabled", havingValue = "true")
public class RsiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RsiConfiguration.class);

    /** 回放验证引擎（无副作用、不持有 DecisionPort——结构性排除实时判定调用）。 */
    @Bean
    public ReplayEngine replayEngine() {
        log.info("[RSI] assembling R0 replay engine (offline counterfactual validation, candidate-only)");
        return new ReplayEngine();
    }

    /** 轨迹仓库（缺省内存实现；生产可覆盖为 JPA/快照级实现，P5 开闭）。 */
    @Bean
    @ConditionalOnMissingBean(TrajectoryRepository.class)
    public TrajectoryRepository trajectoryRepository() {
        return new InMemoryTrajectoryRepository();
    }

    /** 蒸馏抽取器（R2）：M5/M6 大模型归纳或确定性模板，P5 开闭；缺省模板零网络。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.distillation.enabled", havingValue = "true")
    public DistillationExtractor distillationExtractor(RsiProperties properties,
                                                       ObjectProvider<LlmGateway> llmGatewayProvider) {
        if (properties.getDistillation().isLlmEnabled()) {
            LlmGateway gateway = llmGatewayProvider.getIfAvailable();
            if (gateway != null) {
                log.info("[RSI] assembling R2 distillation extractor: M5/M6 LLM (REASONING)");
                return new LlmDistillationExtractor(gateway);
            }
        }
        log.info("[RSI] assembling R2 distillation extractor: deterministic template (offline)");
        return new TemplateDistillationExtractor();
    }

    /** 记忆自蒸馏器（R2）：Jev 置信门 + 提炼 + 语义去重 + 落 L4（namespace 隔离）。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.distillation.enabled", havingValue = "true")
    public MemoryDistiller memoryDistiller(DistillationExtractor extractor, RsiProperties properties) {
        log.info("[RSI] assembling R2 memory distiller (minConfidence={}, dedupThreshold={}, namespace={})",
                properties.getDistillation().getMinConfidence(),
                properties.getDistillation().getDedupThreshold(),
                properties.getDistillation().getNamespace());
        return new MemoryDistiller(extractor,
                properties.getDistillation().getMinConfidence(),
                properties.getDistillation().getDedupThreshold(),
                properties.getDistillation().getNamespace());
    }

    /** 技能自合成器（R3）：确定性模板归纳（M5 归纳为后续增强）。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.synthesis.enabled", havingValue = "true")
    public SkillSynthesizer skillSynthesizer() {
        log.info("[RSI] assembling R3 skill synthesizer (deterministic template induction, candidate-only)");
        return new SkillSynthesizer();
    }

    /** 技能合成校验器（R3）：越权审查 + 只降本红线（离线确定性）。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.synthesis.enabled", havingValue = "true")
    public SkillSynthesisValidator skillSynthesisValidator() {
        log.info("[RSI] assembling R3 skill synthesis validator (allowlist + cost guard, P11)");
        return new SkillSynthesisValidator();
    }

    /** 合成技能注册器（R3）：版本化 + 一键回滚。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.synthesis.enabled", havingValue = "true")
    public SynthesizedSkillRegistrar synthesizedSkillRegistrar() {
        log.info("[RSI] assembling R3 synthesized skill registrar (versioned + rollback)");
        return new SynthesizedSkillRegistrar();
    }

    /** RSI 提案仓库（R-G，缺省内存实现；生产可覆盖 JPA，P5 开闭）。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.governance.enabled", havingValue = "true")
    @ConditionalOnMissingBean(RsiProposalRepository.class)
    public RsiProposalRepository rsiProposalRepository() {
        return new InMemoryRsiProposalRepository();
    }

    /** RSI 安全平面（R-G，P0）：提案-验证-应用三段式 + 红线/深度/频率/人审护栏。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.governance.enabled", havingValue = "true")
    public RsiSafetyPlane rsiSafetyPlane(RsiProposalRepository repository, RsiProperties properties) {
        log.info("[RSI] assembling R-G safety plane (maxDepth={}, maxProposalsPerMinute={}, forbidden={})",
                properties.getGovernance().getMaxDepth(),
                properties.getGovernance().getMaxProposalsPerMinute(),
                properties.getGovernance().getForbiddenTargetPrefixes());
        return new RsiSafetyPlane(repository,
                properties.getGovernance().getMaxDepth(),
                properties.getGovernance().getMaxProposalsPerMinute(),
                properties.getGovernance().getForbiddenTargetPrefixes());
    }

    /** 策略自优化器（R4）：R0 回放择优 + THRESHOLD 提案 + 劣化自动回滚（达 D3）。 */
    @Bean
    @ConditionalOnProperty(name = "langur.rsi.optimization.enabled", havingValue = "true")
    public StrategyOptimizer strategyOptimizer(ReplayEngine replayEngine) {
        log.info("[RSI] assembling R4 strategy optimizer (replay bandit + threshold tuning + auto-rollback)");
        return new StrategyOptimizer(replayEngine);
    }
}
