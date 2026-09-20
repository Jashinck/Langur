package org.skylark.langur.domain.port;

/**
 * 容错降级策略（§8.2）：LLM 超时/异常、工具执行失败时的兜底话术。
 * <p>领域端口，默认实现 {@link DefaultFallbackStrategy}，可由业务扩展定制降级文案/切换备用模型。</p>
 */
public interface FallbackStrategy {

    /** LLM 调用失败时的兜底最终答案 */
    String onLlmFailure(String model, String userMessage, Throwable error);

    /** 工具多次重试仍失败时的降级观测结果 */
    String onToolFailure(String toolName, String lastError);
}
