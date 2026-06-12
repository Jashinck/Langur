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

import java.util.List;
import java.util.Map;

@Slf4j
public abstract class AbstractOpenAICompatibleLLMAdapter implements ModelRoutableLLMPort {

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final LlmProperties.ProviderProperties providerProperties;

    protected AbstractOpenAICompatibleLLMAdapter(LlmProperties llmProperties,
                                                 ObjectMapper objectMapper,
                                                 String providerName) {
        this.objectMapper = objectMapper;
        this.providerProperties = llmProperties.getProvider(providerName);
        this.webClient = WebClient.builder()
                .baseUrl(StringUtils.defaultIfBlank(providerProperties.getBaseUrl(), defaultBaseUrl()))
                .build();
    }

    @Override
    public LLMDecision decide(String systemPrompt,
                              String model,
                              List<Map<String, String>> conversationHistory,
                              List<Tool> availableTools) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", resolveModel(model));

            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            for (Map<String, String> msg : conversationHistory) {
                ObjectNode msgNode = messages.addObject();
                msg.forEach(msgNode::put);
            }

            if (!availableTools.isEmpty()) {
                ArrayNode tools = requestBody.putArray("tools");
                for (Tool tool : availableTools) {
                    ObjectNode toolNode = tools.addObject();
                    toolNode.put("type", "function");
                    ObjectNode fn = toolNode.putObject("function");
                    fn.put("name", tool.getName());
                    fn.put("description", tool.getDescription());
                    fn.set("parameters", objectMapper.valueToTree(tool.getDefinition().getParametersSchema()));
                }
            }

            String response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + providerProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseDecision(response);
        } catch (Exception e) {
            log.error("{} LLM call failed", getProviderName(), e);
            return LLMDecision.finalAnswer("Error communicating with LLM: " + e.getMessage());
        }
    }

    @Override
    public String complete(String systemPrompt, String model, String userMessage) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", resolveModel(model));
            ArrayNode messages = requestBody.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userMessage);

            String response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + providerProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            return root.at("/choices/0/message/content").asText();
        } catch (Exception e) {
            log.error("{} LLM completion failed", getProviderName(), e);
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    protected LLMDecision parseDecision(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode message = root.at("/choices/0/message");

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            JsonNode call = toolCalls.get(0);
            String toolName = call.at("/function/name").asText();
            String argsJson = call.at("/function/arguments").asText("{}");
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            String thought = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText() : "Calling tool: " + toolName;
            return LLMDecision.toolCall(thought, toolName, args);
        }

        return LLMDecision.finalAnswer(message.at("/content").asText());
    }

    protected String resolveModel(String model) {
        return StringUtils.defaultIfBlank(model, providerProperties.getModel());
    }

    protected abstract String defaultBaseUrl();
}
