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
@ConditionalOnProperty(prefix = "langur.llm.providers.gemini", name = "enabled", havingValue = "true")
public class GeminiLLMAdapter implements ModelRoutableLLMPort {

    private static final String PROVIDER = "gemini";
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final LlmProperties.ProviderProperties providerProperties;

    public GeminiLLMAdapter(LlmProperties llmProperties, ObjectMapper objectMapper) {
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
        return model != null && model.startsWith("gemini-");
    }

    @Override
    public LLMPort.LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> conversationHistory,
                                      List<Tool> availableTools) {
        try {
            ObjectNode requestBody = buildRequest(systemPrompt, conversationHistory, availableTools);
            String response = execute(resolveModel(model), requestBody);
            return parseDecision(response);
        } catch (Exception e) {
            log.error("Gemini LLM call failed", e);
            return LLMDecision.finalAnswer("Error communicating with LLM: " + e.getMessage());
        }
    }

    @Override
    public String complete(String systemPrompt, String model, String userMessage) {
        try {
            ObjectNode requestBody = buildRequest(systemPrompt, List.of(Map.of("role", "user", "content", userMessage)), List.of());
            String response = execute(resolveModel(model), requestBody);
            return extractText(response);
        } catch (Exception e) {
            log.error("Gemini completion failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private ObjectNode buildRequest(String systemPrompt, List<Map<String, String>> conversationHistory,
                                    List<Tool> availableTools) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        ObjectNode systemInstruction = requestBody.putObject("systemInstruction");
        ArrayNode sysParts = systemInstruction.putArray("parts");
        sysParts.addObject().put("text", systemPrompt);

        ArrayNode contents = requestBody.putArray("contents");
        for (Map<String, String> message : conversationHistory) {
            ObjectNode messageNode = contents.addObject();
            messageNode.put("role", "assistant".equals(message.get("role")) ? "model" : "user");
            ArrayNode parts = messageNode.putArray("parts");
            parts.addObject().put("text", mapContent(message));
        }

        if (!availableTools.isEmpty()) {
            ArrayNode tools = requestBody.putArray("tools");
            ObjectNode toolNode = tools.addObject();
            ArrayNode declarations = toolNode.putArray("functionDeclarations");
            for (Tool tool : availableTools) {
                ObjectNode declaration = declarations.addObject();
                declaration.put("name", tool.getName());
                declaration.put("description", tool.getDescription());
                declaration.set("parameters", objectMapper.valueToTree(tool.getDefinition().getParametersSchema()));
            }
        }
        return requestBody;
    }

    @SuppressWarnings("unchecked")
    private LLMDecision parseDecision(String responseJson) throws Exception {
        JsonNode parts = firstParts(responseJson);
        List<String> texts = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode functionCall = part.path("functionCall");
                return LLMDecision.toolCall(
                        String.join("\n", texts),
                        functionCall.path("name").asText(),
                        objectMapper.convertValue(functionCall.path("args"), Map.class));
            }
            if (part.has("text")) {
                texts.add(part.path("text").asText());
            }
        }
        return LLMDecision.finalAnswer(String.join("\n", texts));
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode parts = firstParts(responseJson);
        List<String> texts = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("text")) {
                texts.add(part.path("text").asText());
            }
        }
        return String.join("\n", texts);
    }

    private JsonNode firstParts(String responseJson) throws Exception {
        return objectMapper.readTree(responseJson)
                .path("candidates").path(0)
                .path("content").path("parts");
    }

    private String execute(String model, ObjectNode requestBody) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:generateContent")
                        .queryParam("key", providerProperties.getApiKey())
                        .build(model))
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String resolveModel(String model) {
        return StringUtils.defaultIfBlank(model, providerProperties.getModel());
    }

    private String mapContent(Map<String, String> message) {
        if ("tool".equals(message.get("role"))) {
            return "Tool " + message.getOrDefault("name", "unknown") + " returned: "
                    + message.getOrDefault("content", "");
        }
        return message.getOrDefault("content", "");
    }
}
