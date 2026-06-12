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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "langur.llm.providers.claude", name = "enabled", havingValue = "true")
public class ClaudeLLMAdapter implements ModelRoutableLLMPort {

    private static final String PROVIDER = "claude";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final LlmProperties.ProviderProperties providerProperties;

    public ClaudeLLMAdapter(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.providerProperties = llmProperties.getProvider(PROVIDER);
        this.webClient = WebClient.builder()
                .baseUrl(StringUtils.defaultIfBlank(providerProperties.getBaseUrl(), DEFAULT_BASE_URL))
                .build();
    }

    @Override
    public String getProviderName() {
        return PROVIDER;
    }

    @Override
    public boolean supportsModel(String model) {
        return model != null && model.startsWith("claude-");
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
            log.error("Claude LLM call failed", e);
            return LLMDecision.finalAnswer("Error communicating with LLM: " + e.getMessage());
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
            log.error("Claude completion failed", e);
            return "Error: " + e.getMessage();
        }
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
        JsonNode content = objectMapper.readTree(responseJson).path("content");
        String thought = extractText(responseJson);
        for (JsonNode node : content) {
            if ("tool_use".equals(node.path("type").asText())) {
                return LLMDecision.toolCall(
                        StringUtils.defaultIfBlank(thought, "Calling tool: " + node.path("name").asText()),
                        node.path("name").asText(),
                        objectMapper.convertValue(node.path("input"), Map.class));
            }
        }
        return LLMDecision.finalAnswer(thought);
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
