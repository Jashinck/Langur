package org.skylark.langur.domain.harness.evaluation.alert;

import lombok.Builder;
import lombok.Getter;

/**
 * 告警阈值（§10.3）- 配置化驱动（P9），由 start 层从属性构建后注入 {@link AlertEvaluator}。
 */
@Getter
@Builder
public class AlertThresholds {

    /** P1：单次执行耗时上限（毫秒），超过判为严重延迟。 */
    @Builder.Default
    private final long latencyMillis = 5_000L;

    /** P2：单任务 Token 消耗预警阈值。 */
    @Builder.Default
    private final long tokenThreshold = 32_000L;

    /** P0：安全拦截次数达到该值判为致命（越权/资损/泄露需立即关注）。 */
    @Builder.Default
    private final int interceptionThreshold = 1;

    /** P1：工具成功率下限（0..1），低于则判为严重。 */
    @Builder.Default
    private final double toolSuccessRateFloor = 0.95d;

    public static AlertThresholds defaults() {
        return AlertThresholds.builder().build();
    }
}
