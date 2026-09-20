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
}
