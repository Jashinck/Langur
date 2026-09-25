package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 适配器模板（H13.1）- 由 {@code ModelProviderFactory} 按
 * {@code type=anthropic} 配置构造，不再每厂硬编码 @ConditionalOnProperty Bean。
 */
@Slf4j
public class ClaudeLLMAdapter implements ModelRoutableLLMPort {

    private final String providerName;
    private final List<String> modelPrefixes;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final LlmProperties.ProviderProperties providerProperties;

    public ClaudeLLMAdapter(String providerName,
                            LlmProperties.ProviderProperties providerProperties,
                            String defaultBaseUrl,
                            List<String> modelPrefixes,
                            ObjectMapper objectMapper) {
        this.providerName = providerName;
        this.modelPrefixes = modelPrefixes;
        this.objectMapper = objectMapper;
        this.providerProperties = providerProperties;
        this.webClient = WebClient.builder()
                .baseUrl(StringUtils.defaultIfBlank(providerProperties.getBaseUrl(), defaultBaseUrl))
                .build();
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public boolean supportsModel(String model) {
        return ModelPrefixes.matches(model, modelPrefixes, providerProperties.getModel());
    }

    @Override
    public LLMPort.LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> conversationHistory,
                                      List<Tool> availableTools) {
        try {
            ObjectNode requestBody = buildBaseRequest(systemPrompt, resolveModel(model), conversationHistory);
            if (!availableTools.isEmpty()) {
                ArrayNode toolsNode = requestBody.putArray("tools");
                for (Tool tool : availableTools) {
                    ObjectNode toolNode = toolsNode.addObject();
                    toolNode.put("name", tool.getName());
                    toolNode.put("description", tool.getDescription());
                    toolNode.set("input_schema", objectMapper.valueToTree(tool.getDefinition().getParametersSchema()));
                }
            }
            String response = execute(requestBody);
            return parseDecision(response);
        } catch (Exception e) {
            throw providerException("decide", e);
        }
    }

    @Override
    public String complete(String systemPrompt, String model, String userMessage) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", resolveModel(model));
            requestBody.put("max_tokens", 4096);
            requestBody.put("system", systemPrompt);
            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            content.addObject().put("type", "text").put("text", userMessage);
            return extractText(execute(requestBody));
        } catch (Exception e) {
            throw providerException("complete", e);
        }
    }

    /** 传输层异常统一转 {@link ModelProviderException}（H13.4），不打完整堆栈防密钥泄露。 */
    private ModelProviderException providerException(String operation, Exception cause) {
        log.debug("Claude {} failed", operation, cause);
        return new ModelProviderException(getProviderName(), operation, cause);
    }

    private ObjectNode buildBaseRequest(String systemPrompt, String model,
                                        List<Map<String, String>> conversationHistory) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", 4096);
        requestBody.put("system", systemPrompt);
        ArrayNode messages = requestBody.putArray("messages");
        for (Map<String, String> message : conversationHistory) {
            String role = message.getOrDefault("role", "user");
            ObjectNode messageNode = messages.addObject();
            messageNode.put("role", mapRole(role));
            ArrayNode content = messageNode.putArray("content");
            content.addObject().put("type", "text").put("text", mapContent(message));
        }
        return requestBody;
    }

    @SuppressWarnings("unchecked")
    private LLMDecision parseDecision(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode content = root.path("content");
        LLMPort.TokenUsage usage = parseUsage(root);
        String thought = extractText(responseJson);
        for (JsonNode node : content) {
            if ("tool_use".equals(node.path("type").asText())) {
                return LLMDecision.toolCall(
                        StringUtils.defaultIfBlank(thought, "Calling tool: " + node.path("name").asText()),
                        node.path("name").asText(),
                        objectMapper.convertValue(node.path("input"), Map.class),
                        usage);
            }
        }
        return LLMDecision.finalAnswer(thought, usage);
    }

    /**
     * 解析 Anthropic usage（H1）：input_tokens / output_tokens；缺失或全零返回 null。
     */
    private LLMPort.TokenUsage parseUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        long prompt = usage.path("input_tokens").asLong(0);
        long completion = usage.path("output_tokens").asLong(0);
        long total = prompt + completion;
        return total <= 0 ? null : LLMPort.TokenUsage.of(prompt, completion, total);
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode content = objectMapper.readTree(responseJson).path("content");
        List<String> texts = new ArrayList<>();
        for (JsonNode node : content) {
            if ("text".equals(node.path("type").asText())) {
                texts.add(node.path("text").asText());
            }
        }
        return String.join("\n", texts);
    }

    private String execute(ObjectNode requestBody) {
        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", providerProperties.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String resolveModel(String model) {
        return StringUtils.defaultIfBlank(model, providerProperties.getModel());
    }

    private String mapRole(String role) {
        return "assistant".equals(role) ? "assistant" : "user";
    }

    private String mapContent(Map<String, String> message) {
        if ("tool".equals(message.get("role"))) {
            return "Tool " + message.getOrDefault("name", "unknown") + " returned: "
                    + message.getOrDefault("content", "");
        }
        return message.getOrDefault("content", "");
    }
}
