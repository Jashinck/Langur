package org.skylark.langur.domain.harness.evaluation.tracing;

/**
 * 六类链路 Span（§10.2）：API 接入 / 上下文构建 / LLM 推理 / 工具调用 / 状态写入 / 响应输出。
 */
public enum SpanType {
    API,
    CONTEXT,
    INFERENCE,
    TOOL,
    SNAPSHOT,
    OUTPUT
}
