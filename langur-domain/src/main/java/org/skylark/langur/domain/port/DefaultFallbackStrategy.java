package org.skylark.langur.domain.port;

/**
 * 默认降级策略：返回稳定的兜底话术，保证主链路不因单点故障中断（§8.2）。
 */
public class DefaultFallbackStrategy implements FallbackStrategy {

    @Override
    public String onLlmFailure(String model, String userMessage, Throwable error) {
        String reason = error != null ? error.getMessage() : "unknown";
        return "抱歉，模型服务暂时不可用，请稍后重试。（model=" + model + ", cause=" + reason + "）";
    }

    @Override
    public String onToolFailure(String toolName, String lastError) {
        return "工具 [" + toolName + "] 多次执行失败，已降级处理。原因：" + lastError;
    }
}
