package org.skylark.langur.domain.port;

import org.skylark.langur.domain.model.message.SessionEvent;
import org.skylark.langur.domain.model.message.ToolCall;
import org.skylark.langur.domain.model.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * LLM端口接口 - 依赖倒置，领域层不依赖具体LLM实现
 */
public interface LLMPort {

    LLMDecision decide(String systemPrompt,
                       String model,
                       List<SessionEvent> sessionEvents,
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
        private final List<ToolCall> toolCalls;
        private final String answer;
        private final TokenUsage usage;

        private LLMDecision(boolean finalAnswer, String thought,
                             List<ToolCall> toolCalls,
                             String answer, TokenUsage usage) {
            this.finalAnswer = finalAnswer;
            this.thought = thought;
            this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            this.answer = answer;
            this.usage = usage;
        }

        /** 单工具调用（无 provider id），便捷工厂。 */
        public static LLMDecision toolCall(String thought, String toolName, Map<String, Object> args) {
            return toolCall(thought, List.of(ToolCall.of(toolName, args)));
        }

        /** 单工具调用（无 provider id），便捷工厂。 */
        public static LLMDecision toolCall(String thought, String toolName, Map<String, Object> args, TokenUsage usage) {
            return toolCall(thought, List.of(ToolCall.of(toolName, args)), usage);
        }

        /** 原生工具调用列表（H15.1：并行/多工具调用全量保留，不再丢弃 tool_calls[1..n]）。 */
        public static LLMDecision toolCall(String thought, List<ToolCall> toolCalls) {
            return new LLMDecision(false, thought, toolCalls, null, null);
        }

        public static LLMDecision toolCall(String thought, List<ToolCall> toolCalls, TokenUsage usage) {
            return new LLMDecision(false, thought, toolCalls, null, usage);
        }

        public static LLMDecision finalAnswer(String answer) {
            return new LLMDecision(true, null, null, answer, null);
        }

        public static LLMDecision finalAnswer(String answer, TokenUsage usage) {
            return new LLMDecision(true, null, null, answer, usage);
        }

        public boolean isFinalAnswer() { return finalAnswer; }
        public String getThought() { return thought; }
        public List<ToolCall> getToolCalls() { return toolCalls; }
        /** 首个工具名（单工具调用场景便捷访问；多工具调用应遍历 {@link #getToolCalls()}）。 */
        public String getToolName() { return toolCalls.isEmpty() ? null : toolCalls.get(0).name(); }
        /** 首个工具参数（同上便捷访问）。 */
        public Map<String, Object> getToolArguments() { return toolCalls.isEmpty() ? Map.of() : toolCalls.get(0).arguments(); }
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
