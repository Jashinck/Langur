package org.skylark.langur.domain.port;

import org.skylark.langur.domain.model.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * LLM端口接口 - 依赖倒置，领域层不依赖具体LLM实现
 */
public interface LLMPort {

    LLMDecision decide(String systemPrompt,
                       String model,
                       List<Map<String, String>> conversationHistory,
                       List<Tool> availableTools);

    String complete(String systemPrompt, String model, String userMessage);

    /**
     * 补全并回传真实 Token 计量（H13.5）：默认委派 {@link #complete}，usage 为空计量，
     * 由调用方按 H1 语义降级为字符估算；OpenAI 兼容适配器覆写解析响应 usage 字段。
     */
    default CompletionResult completeWithUsage(String systemPrompt, String model, String userMessage) {
        return new CompletionResult(complete(systemPrompt, model, userMessage), TokenUsage.empty());
    }

    /**
     * 流式补全（T8）：逐块回调 token，供 SSE 双入口消费。
     * <p>默认降级为非流式一次性产出；OpenAI 兼容适配器覆写为真实 stream=true SSE 流。</p>
     */
    default void streamComplete(String systemPrompt,
                                String model,
                                String userMessage,
                                java.util.function.Consumer<String> tokenConsumer) {
        String full = complete(systemPrompt, model, userMessage);
        if (full != null && !full.isEmpty()) {
            tokenConsumer.accept(full);
        }
    }

    /**
     * Token 计量值对象（H1）：LLM 响应 usage 字段的领域映射，纯 JDK 零外部依赖。
     * <p>真实 usage 缺失时 {@link #isEmpty()} 为真，调用方降级为字符估算。</p>
     */
    class TokenUsage {
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;

        public TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public static TokenUsage of(long promptTokens, long completionTokens) {
            return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        }

        /** 空计量（H13.5）：{@link #isEmpty()} 为真，语义与 null 等同，供默认实现返回。 */
        public static TokenUsage empty() {
            return new TokenUsage(0, 0, 0);
        }

        public static TokenUsage of(long promptTokens, long completionTokens, long totalTokens) {
            return new TokenUsage(promptTokens, completionTokens, totalTokens);
        }

        public long getPromptTokens() { return promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public long getTotalTokens() { return totalTokens; }

        /** 无有效计量（provider 未回传 usage 或全为 0）。 */
        public boolean isEmpty() { return totalTokens <= 0; }
    }

    class LLMDecision {
        private final boolean finalAnswer;
        private final String thought;
        private final String toolName;
        private final Map<String, Object> toolArguments;
        private final String answer;
        private final TokenUsage usage;

        private LLMDecision(boolean finalAnswer, String thought,
                             String toolName, Map<String, Object> toolArguments,
                             String answer, TokenUsage usage) {
            this.finalAnswer = finalAnswer;
            this.thought = thought;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
            this.answer = answer;
            this.usage = usage;
        }

        public static LLMDecision toolCall(String thought, String toolName, Map<String, Object> args) {
            return new LLMDecision(false, thought, toolName, args, null, null);
        }

        public static LLMDecision toolCall(String thought, String toolName, Map<String, Object> args, TokenUsage usage) {
            return new LLMDecision(false, thought, toolName, args, null, usage);
        }

        public static LLMDecision finalAnswer(String answer) {
            return new LLMDecision(true, null, null, null, answer, null);
        }

        public static LLMDecision finalAnswer(String answer, TokenUsage usage) {
            return new LLMDecision(true, null, null, null, answer, usage);
        }

        public boolean isFinalAnswer() { return finalAnswer; }
        public String getThought() { return thought; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getToolArguments() { return toolArguments; }
        public String getFinalAnswer() { return answer; }
        /** 本轮真实 Token 计量；provider 未回传时为 {@code null}（H1）。 */
        public TokenUsage getUsage() { return usage; }
    }

    /**
     * 补全结果值对象（H13.5）：content + 真实 Token 计量，纯 JDK 零外部依赖。
     * <p>{@link #getUsage()} 为空计量（{@link TokenUsage#isEmpty()}）时，调用方按 H1 语义降级估算。</p>
     */
    final class CompletionResult {
        private final String content;
        private final TokenUsage usage;

        public CompletionResult(String content, TokenUsage usage) {
            this.content = content;
            this.usage = usage != null ? usage : TokenUsage.empty();
        }

        public String getContent() { return content; }
        public TokenUsage getUsage() { return usage; }
    }
}
