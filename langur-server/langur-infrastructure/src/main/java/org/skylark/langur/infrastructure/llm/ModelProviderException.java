package org.skylark.langur.infrastructure.llm;

/**
 * Provider 传输层异常（H13.4）- 适配器在传输/HTTP 非 2xx/反序列化失败时抛出，
 * 由 {@link LlmGateway#withFallback} 捕获并按 {@code fallback-chains} 逐级降级。
 * <p>取代旧"吞异常返回 {@code "Error: ..."} 字符串"的行为（该行为导致降级链永不触发）。</p>
 * <p>安全约定：{@link #getMessage()} 不拼接底层异常文本（Gemini 的 key 走查询参数，
 * 底层 WebClient 异常消息可能携带完整 URI）；细节仅保留在 cause 中供排查，不落网关 warn 日志。</p>
 */
public class ModelProviderException extends RuntimeException {

    private final String provider;

    public ModelProviderException(String provider, String operation, Throwable cause) {
        super("LLM provider [" + provider + "] " + operation + " failed: "
                + cause.getClass().getSimpleName(), cause);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}
