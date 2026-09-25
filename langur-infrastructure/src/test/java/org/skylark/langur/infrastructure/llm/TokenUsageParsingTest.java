package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * H1 验收（适配器侧）- OpenAI 兼容响应 usage 字段解析回填到 LLMDecision；缺失时为 null 由上层降级。
 * <p>parseDecision 为纯字符串解析，不触发网络调用。</p>
 */
class TokenUsageParsingTest {

    private final OpenAILLMAdapter adapter = new OpenAILLMAdapter(new LlmProperties(), new ObjectMapper());

    @Test
    void shouldParseUsageOnFinalAnswer() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}";

        LLMPort.LLMDecision decision = adapter.parseDecision(json);

        assertNotNull(decision.getUsage());
        assertEquals(11, decision.getUsage().getPromptTokens());
        assertEquals(7, decision.getUsage().getCompletionTokens());
        assertEquals(18, decision.getUsage().getTotalTokens());
    }

    @Test
    void shouldParseUsageOnToolCall() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":"
                + "{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"BJ\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}";

        LLMPort.LLMDecision decision = adapter.parseDecision(json);

        assertEquals("get_weather", decision.getToolName());
        assertNotNull(decision.getUsage());
        assertEquals(25, decision.getUsage().getTotalTokens());
    }

    @Test
    void shouldReturnNullUsageWhenAbsent() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";

        LLMPort.LLMDecision decision = adapter.parseDecision(json);

        assertNull(decision.getUsage(), "无 usage 字段时应为 null，交由上层降级估算");
    }

    @Test
    void shouldReturnNullUsageWhenAllZero() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";

        assertNull(adapter.parseDecision(json).getUsage());
    }
}
