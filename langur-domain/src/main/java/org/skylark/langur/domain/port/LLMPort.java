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

    class LLMDecision {
        private final boolean finalAnswer;
        private final String thought;
        private final String toolName;
        private final Map<String, Object> toolArguments;
        private final String answer;

        private LLMDecision(boolean finalAnswer, String thought,
                             String toolName, Map<String, Object> toolArguments,
                             String answer) {
            this.finalAnswer = finalAnswer;
            this.thought = thought;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
            this.answer = answer;
        }

        public static LLMDecision toolCall(String thought, String toolName, Map<String, Object> args) {
            return new LLMDecision(false, thought, toolName, args, null);
        }

        public static LLMDecision finalAnswer(String answer) {
            return new LLMDecision(true, null, null, null, answer);
        }

        public boolean isFinalAnswer() { return finalAnswer; }
        public String getThought() { return thought; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getToolArguments() { return toolArguments; }
        public String getFinalAnswer() { return answer; }
    }
}
