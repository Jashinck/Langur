package org.skylark.langur.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * 日志型 Span 导出器（T12，§10.2）。
 * <p>在无 OTel Collector 环境下将完整 Span 链输出至日志，便于观测一次请求的六类 Span 时序。
 * 使用 SDK 的 {@link SpanExporter} 接口自实现，避免引入额外 exporter 依赖。</p>
 */
public class LoggingSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(LoggingSpanExporter.class);
    private static final AttributeKey<String> SPAN_TYPE = AttributeKey.stringKey("langur.span.type");

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData span : spans) {
            log.info("[V][span] traceId={} spanId={} name={} type={} status={} elapsedMillis={} attributes={}",
                    span.getTraceId(),
                    span.getSpanId(),
                    span.getName(),
                    span.getAttributes().get(SPAN_TYPE),
                    span.getStatus().getStatusCode(),
                    (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000,
                    span.getAttributes());
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
