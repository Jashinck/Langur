package org.skylark.langur.config;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.rsi.ReplayEngine;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.InMemoryTrajectoryRepository;
import org.skylark.langur.infrastructure.harness.rsi.RsiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * R0 验收 - {@link RsiConfiguration} 装配离线单测（直接调用 Bean 工厂方法 + 注解断言）。
 * <p>验收点：默认关闭（{@code @ConditionalOnProperty} 无 matchIfMissing，enabled=false 时整个配置类 back-off
 * 不产 Bean，P10/P11 RSI 暂停态）；开启后装配 R0 回放底座（{@link ReplayEngine} + 缺省内存 {@link TrajectoryRepository}）。</p>
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
    }

    @Test
    void shouldAssembleReplayEngineAndInMemoryTrajectoryRepositoryWhenEnabled() {
        assertInstanceOf(ReplayEngine.class, configuration.replayEngine());

        TrajectoryRepository repository = configuration.trajectoryRepository();
        assertInstanceOf(InMemoryTrajectoryRepository.class, repository, "缺省内存轨迹仓库（可被覆盖，P5）");
        assertNotNull(repository.listTaskIds());
    }
}
