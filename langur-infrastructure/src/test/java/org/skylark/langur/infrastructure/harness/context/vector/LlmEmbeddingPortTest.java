package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 验收（M2 语义嵌入）- usage 解析对齐配置维度、维度配置化、provider 不可用时降级本地哈希嵌入（P10）。
 * <p>parseEmbedding 为纯字符串解析；降级路径指向不可达地址，离线确定触发。</p>
 */
class LlmEmbeddingPortTest {

    private LlmEmbeddingPort newPort(int dimensions) {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setType("llm");
        props.setDimensions(dimensions);
        props.setBaseUrl("http://127.0.0.1:1");   // 不可达 → 触发降级
        props.setApiKey("test-key");
        props.setTimeoutSeconds(1);
        LlmProperties llmProperties = new LlmProperties();
        LlmGateway gateway = new LlmGateway(stubLlm(), llmProperties);
        return new LlmEmbeddingPort(props, llmProperties, gateway, new ObjectMapper());
    }

    private LLMPort stubLlm() {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String s, String m, List<Map<String, String>> h, List<Tool> t) {
                return LLMDecision.finalAnswer("x");
            }

            @Override
            public String complete(String s, String m, String u) {
                return "x";
            }
        };
    }

    @Test
    void shouldParseEmbeddingAtConfiguredDimension() throws Exception {
        LlmEmbeddingPort port = newPort(3);
        float[] vector = port.parseEmbedding("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        assertNotNull(vector);
        assertEquals(3, vector.length);
        assertEquals(0.1f, vector[0], 1e-6);
    }

    @Test
    void shouldTruncateWhenResponseLongerThanDimension() throws Exception {
        LlmEmbeddingPort port = newPort(2);
        float[] vector = port.parseEmbedding("{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4]}]}");
        assertEquals(2, vector.length);
    }

    @Test
    void shouldPadWhenResponseShorterThanDimension() throws Exception {
        LlmEmbeddingPort port = newPort(5);
        float[] vector = port.parseEmbedding("{\"data\":[{\"embedding\":[0.5,0.5]}]}");
        assertEquals(5, vector.length);
        assertEquals(0f, vector[4], 1e-6);
    }

    @Test
    void shouldReturnNullForEmptyEmbeddingResponse() throws Exception {
        assertNull(newPort(3).parseEmbedding("{\"data\":[]}"));
    }

    @Test
    void shouldExposeConfiguredDimensions() {
        assertEquals(8, newPort(8).dimensions());
    }

    @Test
    void shouldDegradeToLocalHashEmbeddingWhenProviderUnreachable() {
        LlmEmbeddingPort port = newPort(64);
        float[] vector = port.embed("reset the database password");

        assertEquals(64, vector.length, "降级向量维度须与配置一致");
        double norm = 0d;
        for (float v : vector) {
            norm += (double) v * v;
        }
        assertTrue(norm > 0d, "降级嵌入应为非零向量");
        assertEquals(1.0d, Math.sqrt(norm), 1e-4, "降级嵌入应 L2 归一化");
    }

    @Test
    void shouldReturnZeroVectorForBlankText() {
        float[] vector = newPort(16).embed("   ");
        assertEquals(16, vector.length);
        for (float v : vector) {
            assertEquals(0f, v, 1e-9);
        }
    }
}
