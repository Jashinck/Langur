package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * C 组件 L4 - 语义嵌入实现（H2，M2）。
 * <p>经 provider OpenAI 兼容 {@code /embeddings} 端点产出真实语义向量，模型经 {@link LlmGateway}
 * 走 EMBEDDING 角色解析；维度由 {@link EmbeddingProperties#getDimensions()} 配置化（DD7）。</p>
 * <p>降级兜底（P10）：任一调用失败时回退为本地确定性哈希嵌入（同维度），保证向量库 schema 一致、主链路不中断。
 * 仅在 {@code langur.embedding.type=llm} 时装配，缺省让位给 {@link LexicalEmbeddingPort}。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.embedding.type", havingValue = "llm")
public class LlmEmbeddingPort implements EmbeddingPort {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;
    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public LlmEmbeddingPort(EmbeddingProperties properties,
                            LlmProperties llmProperties,
                            LlmGateway llmGateway,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.llmGateway = llmGateway;
        LlmProperties.ProviderProperties provider =
                llmProperties.getProvider(llmProperties.getDefaultProvider());
        String baseUrl = StringUtils.defaultIfBlank(properties.getBaseUrl(), provider.getBaseUrl());
        this.apiKey = StringUtils.defaultIfBlank(properties.getApiKey(), provider.getApiKey());
        this.model = StringUtils.defaultIfBlank(properties.getModel(),
                llmGateway.resolveModel(ModelRole.EMBEDDING));
        this.webClient = WebClient.builder()
                .baseUrl(StringUtils.defaultIfBlank(baseUrl, "https://api.openai.com/v1"))
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimensions()];
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("dimensions", dimensions());
            ArrayNode input = body.putArray("input");
            input.add(text);

            String response = webClient.post()
                    .uri(properties.getPath())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));

            float[] vector = parseEmbedding(response);
            if (vector != null) {
                return normalize(vector);
            }
            log.warn("[EMBEDDING] empty embedding response, degrade to local hash embedding");
        } catch (Exception e) {
            log.warn("[EMBEDDING] provider call failed, degrade to local hash embedding: {}", e.getMessage());
        }
        return normalize(fallbackEmbed(text));
    }

    @Override
    public int dimensions() {
        return Math.max(1, properties.getDimensions());
    }

    /**
     * 解析 OpenAI 兼容 {@code data[0].embedding}；维度不符时截断/补零对齐到 {@link #dimensions()}。
     */
    float[] parseEmbedding(String responseJson) throws Exception {
        if (StringUtils.isBlank(responseJson)) {
            return null;
        }
        JsonNode embedding = objectMapper.readTree(responseJson).path("data").path(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            return null;
        }
        int dim = dimensions();
        float[] vector = new float[dim];
        for (int i = 0; i < dim && i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    /**
     * 本地确定性哈希嵌入（降级兜底，与配置维度一致）：分词 → 哈希词袋计数。
     */
    private float[] fallbackEmbed(String text) {
        int dim = dimensions();
        float[] vector = new float[dim];
        for (String token : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                vector[Math.floorMod(token.hashCode(), dim)] += 1f;
            }
        }
        return vector;
    }

    private float[] normalize(float[] vector) {
        double norm = 0d;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0d) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }
}
