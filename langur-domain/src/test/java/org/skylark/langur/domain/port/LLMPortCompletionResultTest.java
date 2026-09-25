package org.skylark.langur.domain.port;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.tool.Tool;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13.5 验收（domain 侧）- {@code completeWithUsage} default 委派 {@code complete} 且返回空计量；
 * {@code CompletionResult} 对 null usage 归一为空计量（调用方按 H1 语义降级估算）。
 * <p>纯 JUnit5 + 内部类 stub（domain 禁用 Mockito）。</p>
 */
class LLMPortCompletionResultTest {

    /** 仅实现旧契约的端口：completeWithUsage 走 default 委派。 */
    static class EchoPort implements LLMPort {
        @Override
        public LLMDecision decide(String systemPrompt, String model,
                                  List<Map<String, String>> history, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String complete(String systemPrompt, String model, String userMessage) {
            return "echo:" + userMessage;
        }
    }

    @Test
    void shouldDelegateToCompleteWithEmptyUsageByDefault() {
        LLMPort port = new EchoPort();

        LLMPort.CompletionResult result = port.completeWithUsage("sys", "m", "hi");

        assertEquals("echo:hi", result.getContent(), "默认实现委派旧 complete 保持向后兼容");
        assertTrue(result.getUsage().isEmpty(), "默认实现返回空计量，交由调用方降级估算");
    }

    @Test
    void shouldCarryRealUsageWhenAdapterOverrides() {
        LLMPort port = new EchoPort() {
            @Override
            public CompletionResult completeWithUsage(String systemPrompt, String model, String userMessage) {
                return new CompletionResult("real", TokenUsage.of(11, 7));
            }
        };

        LLMPort.CompletionResult result = port.completeWithUsage("sys", "m", "hi");

        assertFalse(result.getUsage().isEmpty());
        assertEquals(18, result.getUsage().getTotalTokens());
    }

    @Test
    void shouldNormalizeNullUsageToEmpty() {
        LLMPort.CompletionResult result = new LLMPort.CompletionResult("content", null);

        assertEquals("content", result.getContent());
        assertTrue(result.getUsage().isEmpty(), "null usage 归一为空计量，避免调用方判空");
    }
}
