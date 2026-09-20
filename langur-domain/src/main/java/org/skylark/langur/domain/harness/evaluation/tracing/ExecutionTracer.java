package org.skylark.langur.domain.harness.evaluation.tracing;

/**
 * V 组件 - 全链路追踪器（端口，零外部依赖）。
 * <p>基础设施层提供 OpenTelemetry 实现；traceId 从 API 层生成并透传至执行链路（§10.2）。
 * 埋点失败静默降级，不影响主链路（P10）。</p>
 */
public interface ExecutionTracer {

    /**
     * 开启一个指定类型/操作名的 Span；调用方负责 {@link ExecutionSpan#end()}（推荐 try-with-resources）。
     */
    ExecutionSpan startSpan(SpanType type, String operationName);

    /**
     * 当前链路的 traceId（无活动链路时返回空串）。
     */
    String currentTraceId();

    /**
     * 空实现（降级兜底）：不产生任何 Span，用于未装配可观测能力的场景（P10）。
     */
    ExecutionTracer NOOP = new ExecutionTracer() {
        @Override
        public ExecutionSpan startSpan(SpanType type, String operationName) {
            return NoopSpan.INSTANCE;
        }

        @Override
        public String currentTraceId() {
            return "";
        }
    };

    /**
     * 空 Span 单例：所有操作无副作用。
     */
    final class NoopSpan implements ExecutionSpan {
        static final NoopSpan INSTANCE = new NoopSpan();

        private NoopSpan() {
        }

        @Override
        public ExecutionSpan setAttribute(String key, String value) {
            return this;
        }

        @Override
        public ExecutionSpan setAttribute(String key, long value) {
            return this;
        }

        @Override
        public ExecutionSpan setAttribute(String key, boolean value) {
            return this;
        }

        @Override
        public void recordError(Throwable throwable) {
            // no-op
        }

        @Override
        public void end() {
            // no-op
        }
    }
}
