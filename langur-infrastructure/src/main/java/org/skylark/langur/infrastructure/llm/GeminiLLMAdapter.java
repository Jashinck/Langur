package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.model.message.SessionEvent;
import org.skylark.langur.domain.model.message.ToolCall;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Generative REST 适配器模板（H13.1）- 由 {@code ModelProviderFactory} 按
 * {@code type=gemini} 配置构造，不再每厂硬编码 @ConditionalOnProperty Bean。
 */
@Slf4j
public class GeminiLLMAdapter implements ModelRoutableLLMPort {

    private final String providerName;
    private final List<String> modelPrefixes;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final LlmProperties.ProviderProperties providerProperties;

    public GeminiLLMAdapter(String providerName,
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
                                      List<SessionEvent> sessionEvents,
                                      List<Tool> availableTools) {
        try {
            ObjectNode requestBody = buildRequest(systemPrompt, sessionEvents, availableTools);
            String response = execute(resolveModel(model), requestBody);
            return parseDecision(response);
        } catch (Exception e) {
            throw providerException("decide", e);
        }
    }

    @Override
    public String complete(String systemPrompt, String model, String userMessage) {
        try {
            ObjectNode requestBody = buildRequest(systemPrompt, List.of(SessionEvent.user(0L, userMessage)), List.of());
            String response = execute(resolveModel(model), requestBody);
            return extractText(response);
        } catch (Exception e) {
            throw providerException("complete", e);
        }
    }

    /**
     * 传输层异常统一转 {@link ModelProviderException}（H13.4）。
     * Gemini 的 key 走查询参数，底层异常消息可能含完整 URI，故绝不打堆栈/拼接底层消息。
     */
    private ModelProviderException providerException(String operation, Exception cause) {
        log.debug("Gemini {} failed", operation);
        return new ModelProviderException(getProviderName(), operation, cause);
    }

    private ObjectNode buildRequest(String systemPrompt, List<SessionEvent> sessionEvents,
                                    List<Tool> availableTools) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        ObjectNode systemInstruction = requestBody.putObject("systemInstruction");
        ArrayNode sysParts = systemInstruction.putArray("parts");
        sysParts.addObject().put("text", systemPrompt);

        ArrayNode contents = requestBody.putArray("contents");
        for (SessionEvent event : sessionEvents) {
            ObjectNode messageNode = contents.addObject();
            ArrayNode parts = messageNode.putArray("parts");
            switch (event.type()) {
                case USER -> {
                    messageNode.put("role", "user");
                    parts.addObject().put("text", event.content());
                }
                case ASSISTANT -> {
                    messageNode.put("role", "model");
                    if (!event.content().isBlank()) {
                        parts.addObject().put("text", event.content());
                    }
                    for (ToolCall call : event.toolCalls()) {
                        ObjectNode functionCall = parts.addObject().putObject("functionCall");
                        functionCall.put("name", call.name());
                        functionCall.set("args", objectMapper.valueToTree(call.arguments()));
                    }
                }
                case TOOL -> {
                    messageNode.put("role", "user");
                    ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
                    functionResponse.put("name", event.name());
                    functionResponse.putObject("response").put("content", event.content());
                }
            }
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
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        LLMPort.TokenUsage usage = parseUsage(root);
        List<String> texts = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode functionCall = part.path("functionCall");
                calls.add(ToolCall.of(
                        "",
                        functionCall.path("name").asText(),
                        objectMapper.convertValue(functionCall.path("args"), Map.class)));
            }
            if (part.has("text")) {
                texts.add(part.path("text").asText());
            }
        }
        if (!calls.isEmpty()) {
            return LLMDecision.toolCall(String.join("\n", texts), calls, usage);
        }
        return LLMDecision.finalAnswer(String.join("\n", texts), usage);
    }

    /**
     * 解析 Gemini usageMetadata（H1）：优先 candidates[0].usageMetadata，回退顶层；缺失返回 null。
     */
    private LLMPort.TokenUsage parseUsage(JsonNode root) {
        JsonNode meta = root.path("candidates").path(0).path("usageMetadata");
        if (meta.isMissingNode() || meta.isNull()) {
            meta = root.path("usageMetadata");
        }
        if (meta.isMissingNode() || meta.isNull()) {
            return null;
        }
        long prompt = meta.path("promptTokenCount").asLong(0);
        long completion = meta.path("candidatesTokenCount").asLong(0);
        long total = meta.path("totalTokenCount").asLong(prompt + completion);
        return total <= 0 ? null : LLMPort.TokenUsage.of(prompt, completion, total);
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
}
