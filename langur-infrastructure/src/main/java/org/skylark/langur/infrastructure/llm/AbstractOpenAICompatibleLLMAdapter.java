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
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
public abstract class AbstractOpenAICompatibleLLMAdapter implements ModelRoutableLLMPort {

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String providerName;
    private final List<String> modelPrefixes;
    protected final LlmProperties.ProviderProperties providerProperties;

    /**
     * 配置驱动构造（H13.1）- 由 {@code ModelProviderFactory} 按 provider 配置实例化，
     * 不再依赖 @ConditionalOnProperty 每厂一类。
     */
    protected AbstractOpenAICompatibleLLMAdapter(String providerName,
                                                 LlmProperties.ProviderProperties providerProperties,
                                                 String defaultBaseUrl,
                                                 List<String> modelPrefixes,
                                                 ObjectMapper objectMapper) {
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.providerProperties = providerProperties;
        this.modelPrefixes = modelPrefixes;
        this.webClient = WebClient.builder()
                .baseUrl(StringUtils.defaultIfBlank(providerProperties.getBaseUrl(), defaultBaseUrl))
                .build();
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    /** 前缀配置化匹配（H13.3）：配置前缀 → provider.model 派生前缀。 */
    @Override
    public boolean supportsModel(String model) {
        return ModelPrefixes.matches(model, modelPrefixes, providerProperties.getModel());
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
            throw providerException("decide", e);
        }
    }

    @Override
    public String complete(String systemPrompt, String model, String userMessage) {
        return completeWithUsage(systemPrompt, model, userMessage).getContent();
    }

    /**
     * 补全并回传真实 usage（H13.5，复用 H1 {@link #parseUsage}）：provider 未回传 usage 时
     * 返回空计量，由调用方按 H1 语义降级估算。
     */
    @Override
    public LLMPort.CompletionResult completeWithUsage(String systemPrompt, String model, String userMessage) {
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
            String content = root.at("/choices/0/message/content").asText();
            LLMPort.TokenUsage usage = parseUsage(root);
            return new LLMPort.CompletionResult(content, usage != null ? usage : LLMPort.TokenUsage.empty());
        } catch (Exception e) {
            throw providerException("complete", e);
        }
    }

    @Override
    public void streamComplete(String systemPrompt, String model, String userMessage,
                               Consumer<String> tokenConsumer) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", resolveModel(model));
            requestBody.put("stream", true);
            ArrayNode messages = requestBody.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userMessage);

            webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + providerProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToFlux(String.class)
                    .toStream()
                    .forEach(chunk -> extractStreamDelta(chunk).ifPresent(tokenConsumer));
        } catch (Exception e) {
            throw providerException("streamComplete", e);
        }
    }

    /**
     * 传输层异常统一转 {@link ModelProviderException}（H13.4）：只有传输/非 2xx/反序列化失败才抛出；
     * 模型正常返回的内容（即便含 "error" 文本）原样透传，不算失败。
     * 不在适配器层打完整堆栈（底层异常消息可能含密钥查询参数），细节留给上层按需排查。
     */
    protected ModelProviderException providerException(String operation, Exception cause) {
        log.debug("{} {} failed", getProviderName(), operation, cause);
        return new ModelProviderException(getProviderName(), operation, cause);
    }

    private Optional<String> extractStreamDelta(String chunk) {
        try {
            if (StringUtils.isBlank(chunk) || "[DONE]".equals(chunk.trim())) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(chunk);
            String delta = root.at("/choices/0/delta/content").asText(null);
            return StringUtils.isEmpty(delta) ? Optional.empty() : Optional.of(delta);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    protected LLMDecision parseDecision(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode message = root.at("/choices/0/message");
        LLMPort.TokenUsage usage = parseUsage(root);

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            JsonNode call = toolCalls.get(0);
            String toolName = call.at("/function/name").asText();
            String argsJson = call.at("/function/arguments").asText();
            Map<String, Object> args = StringUtils.isBlank(argsJson)
                    ? Map.of()
                    : objectMapper.readValue(argsJson, Map.class);
            String thought = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText() : "Calling tool: " + toolName;
            return LLMDecision.toolCall(thought, toolName, args, usage);
        }

        return LLMDecision.finalAnswer(message.at("/content").asText(), usage);
    }

    /**
     * 解析 OpenAI 兼容响应的 usage 字段（H1）；缺失或全零返回 null，由上层降级为字符估算。
     */
    protected LLMPort.TokenUsage parseUsage(JsonNode root) {
        JsonNode usage = root.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        long total = usage.path("total_tokens").asLong(prompt + completion);
        if (total <= 0) {
            return null;
        }
        return LLMPort.TokenUsage.of(prompt, completion, total);
    }

    protected String resolveModel(String model) {
        return StringUtils.defaultIfBlank(model, providerProperties.getModel());
    }
}
