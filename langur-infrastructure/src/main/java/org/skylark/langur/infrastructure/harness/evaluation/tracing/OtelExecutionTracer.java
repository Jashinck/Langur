package org.skylark.langur.infrastructure.harness.evaluation.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;

/**
 * V 组件 - OpenTelemetry 全链路追踪实现（T12，§10.2）。
 * <p>以注入的 {@link OpenTelemetry} 构建六类 Span；未注册 SDK 时自动退化为 NOOP（API 默认行为），
 * 埋点异常不冒泡，保证主链路不受影响（P10）。</p>
 */
public class OtelExecutionTracer implements ExecutionTracer {

    static final String INSTRUMENTATION_SCOPE = "org.skylark.langur.harness";
    static final String SPAN_TYPE_ATTRIBUTE = "langur.span.type";

    private final Tracer tracer;

    public OtelExecutionTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    @Override
    public ExecutionSpan startSpan(SpanType type, String operationName) {
        Span span = tracer.spanBuilder(operationName)
                .setAttribute(SPAN_TYPE_ATTRIBUTE, type.name())
                .startSpan();
        return new OtelExecutionSpan(span, span.makeCurrent());
    }

    @Override
    public String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    /**
     * OTel Span 包装：属性写入委派给底层 Span，{@link #end()} 先关闭作用域再结束 Span。
     */
    static final class OtelExecutionSpan implements ExecutionSpan {

        private final Span span;
        private final Scope scope;

        OtelExecutionSpan(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        @Override
        public ExecutionSpan setAttribute(String key, String value) {
            span.setAttribute(key, value);
            return this;
        }

        @Override
        public ExecutionSpan setAttribute(String key, long value) {
            span.setAttribute(key, value);
            return this;
        }

        @Override
        public ExecutionSpan setAttribute(String key, boolean value) {
            span.setAttribute(key, value);
            return this;
        }

        @Override
        public void recordError(Throwable throwable) {
            span.recordException(throwable);
            span.setStatus(StatusCode.ERROR, throwable != null ? throwable.getMessage() : "error");
        }

        @Override
        public void end() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
