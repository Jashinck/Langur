package org.skylark.langur.infrastructure.harness.evaluation.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12 验收（基础设施侧）- OpenTelemetry Span 记录：类型/属性写入并可导出，traceId 在链路内可读。
 */
class OtelExecutionTracerTest {

    private OtelExecutionTracer newTracer(InMemorySpanExporter exporter) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return new OtelExecutionTracer(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
    }

    @Test
    void shouldRecordSpanWithTypeAndAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OtelExecutionTracer tracer = newTracer(exporter);

        try (ExecutionSpan span = tracer.startSpan(SpanType.INFERENCE, "llm.reasoning")) {
            span.setAttribute("model", "gpt-4o");
            span.setAttribute("round", 2L);
            span.setAttribute("finalAnswer", true);
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData recorded = spans.get(0);
        assertEquals("llm.reasoning", recorded.getName());
        assertEquals("INFERENCE", recorded.getAttributes().get(AttributeKey.stringKey("langur.span.type")));
        assertEquals("gpt-4o", recorded.getAttributes().get(AttributeKey.stringKey("model")));
        assertEquals(2L, recorded.getAttributes().get(AttributeKey.longKey("round")));
        assertTrue(recorded.getAttributes().get(AttributeKey.booleanKey("finalAnswer")));
    }

    @Test
    void shouldExposeCurrentTraceIdWithinActiveSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OtelExecutionTracer tracer = newTracer(exporter);

        try (ExecutionSpan ignored = tracer.startSpan(SpanType.API, "agent.run")) {
            String traceId = tracer.currentTraceId();
            assertFalse(traceId.isEmpty(), "活动 Span 内应能读取到 traceId");
            assertEquals(32, traceId.length());
        }
    }
}
