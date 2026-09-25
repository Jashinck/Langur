package org.skylark.langur.config;

import org.skylark.langur.domain.harness.rsi.ReplayEngine;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryTrajectoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RSI 装配（R0，start）。<b>默认关闭</b>（P10/P11）：{@code langur.rsi.enabled=false}（或缺省）时整个
 * {@code @Configuration} 因 {@link ConditionalOnProperty} back-off，不产出任何 Bean，系统行为与既有版本完全一致。
 * <p>开启后装配 R0 <b>离线回放验证底座</b>：{@link ReplayEngine}（确定性反事实重放，只重放录制判定、绝不联网）
 * + {@link TrajectoryRepository}（缺省内存实现，可被同类型 Bean 覆盖为 JPA/快照级，P5）。这是 RSI 一切自改进的
 * 安全前提——候选策略须先经回放验证（劣化即拒绝），再经 R-G 灰度 + 高危人审方可生效（P11 红线：回放通过≠生效）。</p>
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
}
