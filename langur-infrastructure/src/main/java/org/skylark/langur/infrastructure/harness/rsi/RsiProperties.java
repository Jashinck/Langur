package org.skylark.langur.infrastructure.harness.rsi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RSI 配置（R0/R1，{@code langur.rsi.*}，P9）。
 * <p>总开关 {@link #enabled} 默认 {@code false}——未开启时 {@code RsiConfiguration} 不装配，不产出任何 RSI Bean，
 * 系统行为与既有版本完全一致（P10/P11 红线：RSI 暂停态）。开启后装配<b>离线回放验证底座</b>
 * （{@code ReplayEngine} + {@code TrajectoryRepository}）+ 可选<b>反思自检 Hook</b>
 * （{@link Reflection}，{@code ReflectionHook} 经 {@code langur.rsi.reflection.enabled} 另行开启）。
 * RSI 自改进产物默认是候选提案，回放通过<b>不等于生效</b>，生效须经 R-G 灰度 + 高危人审（P11）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.rsi")
public class RsiProperties {

    /** 总开关，默认关（P10/P11）。开启后装配 R0 回放底座；关闭时不产出 RSI Bean，行为等价既有版本。 */
    private boolean enabled = false;

    /** R1 反思自检配置（{@code langur.rsi.reflection.*}，RsiProperties/ReflectionHook 共用）。 */
    private Reflection reflection = new Reflection();

    /**
     * R1 反思自检（RSI L1，{@code langur.rsi.reflection.*}）。
     * <p>{@code enabled} 默认 {@code false}；需与总开关 {@code langur.rsi.enabled=true} 同时成立才装配
     * {@code ReflectionHook}。{@code prescreenThreshold} 为 Jev 廉价初筛的质量/置信阈值——初筛判"足够高"则
     * 跳过 M5 升级以控成本（P10），否则升级 M5 反思改写；{@code critiqueSystemPrompt} 可覆盖默认反思提示词。</p>
     */
    @Data
    public static class Reflection {

        /** 反思 Hook 开关，默认关（P11 暂停态；与总开关 AND 后生效）。 */
        private boolean enabled = false;

        /** Jev 初筛阈值（0..1）≈ 决策平面 completion 维 DD11 经验值；初筛判高质 + 高置信则跳过 M5 省钱。 */
        private double prescreenThreshold = 0.85;

        /** M5 自我批评提示词；留空用缺省中文反思提示词。 */
        private String critiqueSystemPrompt = "";
    }
}
