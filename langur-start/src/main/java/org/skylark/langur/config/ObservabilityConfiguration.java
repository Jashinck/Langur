package org.skylark.langur.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.skylark.langur.domain.harness.evaluation.alert.AlertChannel;
import org.skylark.langur.domain.harness.evaluation.alert.AlertEvaluator;
import org.skylark.langur.domain.harness.evaluation.alert.AlertThresholds;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.infrastructure.harness.evaluation.ObservabilityProperties;
import org.skylark.langur.infrastructure.harness.evaluation.tracing.OtelExecutionTracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 可观测装配（T12，§10）。
 * <p>{@code langur.observability.enabled=false}（默认）时提供 OpenTelemetry NOOP，零开销降级；
 * 开启后构建 SDK 并按配置将 Span 链输出至日志。{@link ExecutionTracer} Bean 恒定存在，
 * 由 {@code HarnessConfiguration} 织入执行循环，实现六类 Span 全链路埋点。</p>
 */
@Configuration
public class ObservabilityConfiguration {

    @Bean
    public OpenTelemetry openTelemetry(ObservabilityProperties properties) {
        if (!properties.isEnabled()) {
            return OpenTelemetry.noop();
        }
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), properties.getServiceName())));
        SdkTracerProviderBuilder tracerProvider = SdkTracerProvider.builder().setResource(resource);
        if (properties.isLogSpans()) {
            tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(new LoggingSpanExporter()));
        }
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider.build()).build();
    }

    @Bean
    public ExecutionTracer executionTracer(OpenTelemetry openTelemetry) {
        return new OtelExecutionTracer(openTelemetry);
    }

    /**
     * 告警分级评估器（H5，§10.3）。阈值由 {@code langur.observability.alert.*} 配置化驱动（P9）；
     * {@code alert.enabled=false} 时装配空规则集（评估恒不命中，零副作用）。
     * 通道取容器内 {@link AlertChannel}（默认 {@code LoggingAlertChannel}），缺失则 NOOP 兜底（P10）。
     */
    @Bean
    public AlertEvaluator alertEvaluator(ObservabilityProperties properties,
                                         ObjectProvider<AlertChannel> channelProvider) {
        AlertChannel channel = channelProvider.getIfAvailable(() -> AlertChannel.NOOP);
        ObservabilityProperties.Alert alert = properties.getAlert();
        if (alert == null || !alert.isEnabled()) {
            return new AlertEvaluator(List.of(), channel);
        }
        AlertThresholds thresholds = AlertThresholds.builder()
                .latencyMillis(alert.getLatencyMillis())
                .tokenThreshold(alert.getTokenThreshold())
                .interceptionThreshold(alert.getInterceptionThreshold())
                .toolSuccessRateFloor(alert.getToolSuccessRateFloor())
                .build();
        return AlertEvaluator.withDefaults(thresholds, channel);
    }
}
