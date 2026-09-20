package org.skylark.langur.domain.harness.evaluation.tracing;

/**
 * V 组件 - 链路 Span 抽象（端口，零外部依赖）。
 * <p>基础设施层对接 OpenTelemetry；领域层仅依赖本接口，遵循依赖倒置（P3）与领域零依赖红线（P1）。
 * 以 try-with-resources 使用：{@link #close()} 自动结束 Span。</p>
 */
public interface ExecutionSpan extends AutoCloseable {

    ExecutionSpan setAttribute(String key, String value);

    ExecutionSpan setAttribute(String key, long value);

    ExecutionSpan setAttribute(String key, boolean value);

    void recordError(Throwable throwable);

    void end();

    @Override
    default void close() {
        end();
    }
}
