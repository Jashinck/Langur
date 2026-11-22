package org.skylark.langur.infrastructure.harness.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可观测配置（T12，§10）。
 * <p>{@code enabled=false} 时使用 OpenTelemetry NOOP（零开销降级）；
 * {@code enabled=true} 时构建 SDK 并按 {@code log-spans} 将 Span 链输出至日志。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.observability")
public class ObservabilityProperties {

    /** 是否启用 OpenTelemetry 链路追踪。 */
    private boolean enabled = false;

    /** 服务名（写入 OTel Resource service.name）。 */
    private String serviceName = "langur";

    /** 是否将 Span 以日志形式导出（便于在无 Collector 环境观测完整 Span 链）。 */
    private boolean logSpans = true;

    /**
     * 评估观测后端（H5）：{@code logging}（默认，仅日志）| {@code prometheus}（四维指标 → Micrometer/Prometheus）。
     */
    private String evaluation = "logging";

    /** 告警分级配置（H5，§10.3）。 */
    private Alert alert = new Alert();

    @Data
    public static class Alert {
        /** 是否启用告警分级评估。 */
        private boolean enabled = true;
        /** P1 延迟上限（毫秒）。 */
        private long latencyMillis = 5_000L;
        /** P2 Token 预警阈值。 */
        private long tokenThreshold = 32_000L;
        /** P0 安全拦截致命阈值。 */
        private int interceptionThreshold = 1;
        /** P1 工具成功率下限（0..1）。 */
        private double toolSuccessRateFloor = 0.95d;
    }
}
