package org.skylark.langur.config;

import org.skylark.langur.domain.harness.rsi.DistillationExtractor;
import org.skylark.langur.domain.harness.rsi.MemoryDistiller;
import org.skylark.langur.domain.harness.rsi.ReplayEngine;
import org.skylark.langur.domain.harness.rsi.TemplateDistillationExtractor;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryTrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.LlmDistillationExtractor;
import org.skylark.langur.infrastructure.harness.rsi.RsiProperties;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RSI 装配（R0/R2，start）。<b>默认关闭</b>（P10/P11）：{@code langur.rsi.enabled=false}（或缺省）时整个
 * {@code @Configuration} 因 {@link ConditionalOnProperty} back-off，不产出任何 Bean，系统行为与既有版本完全一致。
 * <p>开启后装配 R0 <b>离线回放验证底座</b>（{@link ReplayEngine} + {@link TrajectoryRepository}）与 R2
 * <b>记忆自蒸馏</b>（{@link MemoryDistiller}，经 {@code langur.rsi.distillation.enabled} 另行开启）。
 * 二者都是 RSI 一切自改进的安全前提——候选策略须先经回放验证（劣化即拒绝），产物是候选提案，
 * 生效须经 R-G 灰度 + 高危人审（P11 红线：回放通过≠生效）。</p>
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
}
