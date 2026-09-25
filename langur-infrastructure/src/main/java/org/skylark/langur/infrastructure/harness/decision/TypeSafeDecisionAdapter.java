package org.skylark.langur.infrastructure.harness.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.domain.port.LLMPort;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策平面首个后端：TypeSafe Jev / System One 适配器（J1）。
 * <p>纯 REST/JSON（无官方 Java SDK，DD13）：用既有 {@link WebClient}（H6/H7）{@code POST} 到
 * {@code base-url}，请求 {@code {state, model, questions{noul|choice|score, instructions, criteria}}}，
 * 支持<b>批量（投机扇出）</b>——一次请求多问题、{@code state} 只发一次；解析 {@code answers{value/distribution,
 * confidence, usage}}。{@code api-key} 由装配层经 H7 {@code SecretResolver} 解析后注入，绝不落日志/明文（P12）。</p>
 * <p>降级兜底（P10/P12）：后端异常/超时/无问题时委派注入的 {@link DecisionPort} 兜底（通常为
 * {@link RuleFallbackDecisionAdapter}），<b>不向调用方抛出</b>。自部署 Kev/Laya 兼容 TypeSafe SDK，
 * 仅换 {@code base-url} + 免鉴权（{@code apiKey} 留空）即可复用本适配器（J3）。</p>
 * <p>本类不带 {@code @Component}：由 J3 {@code DecisionConfiguration} 经 {@code ObjectProvider} 条件装配，
 * 默认关闭时不产出 Bean（P10）。</p>
 */
@Slf4j
public class TypeSafeDecisionAdapter implements DecisionPort {

    private final WebClient webClient;
    private final String model;
    private final String apiKey;
    private final long timeoutMillis;
    private final ObjectMapper objectMapper;
    private final DecisionPort fallback;

    /** 生产装配用：自建 {@link WebClient}（{@code baseUrl} 为完整判定端点）。 */
    public TypeSafeDecisionAdapter(String baseUrl,
                                   String model,
                                   String apiKey,
                                   long timeoutMillis,
                                   ObjectMapper objectMapper,
                                   DecisionPort fallback) {
        this(WebClient.builder().baseUrl(baseUrl).build(),
                model, apiKey, timeoutMillis, objectMapper, fallback);
    }

    /** 测试/复用用：注入已构造的 {@link WebClient}（可带 ExchangeFunction 桩）。 */
    public TypeSafeDecisionAdapter(WebClient webClient,
                                   String model,
                                   String apiKey,
                                   long timeoutMillis,
                                   ObjectMapper objectMapper,
                                   DecisionPort fallback) {
        this.webClient = webClient;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutMillis = timeoutMillis;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        if (request == null || request.hasNoQuestions()) {
            return delegateFallback(request);
        }
        try {
            String body = buildBody(request);
            WebClient.RequestBodySpec spec = webClient.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON);
            if (StringUtils.isNotBlank(apiKey)) {
                spec.header("Authorization", "Bearer " + apiKey);
            }
            String response = spec.bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(Math.max(1L, timeoutMillis)));
            if (StringUtils.isBlank(response)) {
                log.warn("[DECISION] empty Jev response, degrade to fallback");
                return delegateFallback(request);
            }
            return parse(response, request);
        } catch (Exception e) {
            log.warn("[DECISION] Jev backend call failed, degrade to fallback: {}", e.getMessage());
            return delegateFallback(request);
        }
    }

    private DecisionResponse delegateFallback(DecisionRequest request) {
        if (fallback != null && request != null && !request.hasNoQuestions()) {
            return fallback.decide(request);
        }
        return DecisionResponse.of(Map.of());
    }

    /** 构造 Jev 请求体：{@code state} 只发一次，每问题按类型落 {@code choice/noul/score} 子对象。 */
    String buildBody(DecisionRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("state", StringUtils.defaultString(request.state()));
        body.put("model", StringUtils.defaultIfBlank(request.model(), model));
        ArrayNode questions = body.putArray("questions");
        for (DecisionQuestion q : request.questions()) {
            ObjectNode node = questions.addObject();
            node.put("key", q.key());
            ObjectNode typed = node.putObject(q.type().wireName());
            typed.put("instructions", StringUtils.defaultString(q.instructions()));
            if (q.type() == DecisionType.CHOICE && !q.criteria().isEmpty()) {
                ObjectNode criteria = typed.putObject("criteria");
                q.criteria().forEach(criteria::put);
            }
        }
        return body.toString();
    }

    /** 解析 Jev 响应：按问题类型读 choice/value + confidence + distribution，回填 TokenUsage。 */
    DecisionResponse parse(String json, DecisionRequest request) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode answers = root.path("answers");
        Map<String, DecisionAnswer> parsed = new LinkedHashMap<>();
        for (DecisionQuestion q : request.questions()) {
            JsonNode answer = answers.path(q.key());
            if (answer.isMissingNode() || answer.isNull()) {
                continue;
            }
            double confidence = answer.path("confidence").asDouble(0d);
            DecisionAnswer decisionAnswer = switch (q.type()) {
                case CHOICE -> DecisionAnswer.ofChoice(
                        answer.path("choice").asText(null),
                        confidence,
                        parseDistribution(answer.path("distribution")));
                case PROBABILITY -> DecisionAnswer.ofProbability(
                        answer.path("value").asDouble(0d), confidence);
                case SCORE -> DecisionAnswer.ofScore(
                        answer.path("value").asDouble(0d), confidence);
            };
            parsed.put(q.key(), decisionAnswer);
        }
        return new DecisionResponse(parsed, parseUsage(root.path("usage")));
    }

    private Map<String, Double> parseDistribution(JsonNode node) {
        Map<String, Double> distribution = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    distribution.put(entry.getKey(), entry.getValue().asDouble(0d)));
        }
        return distribution;
    }

    private LLMPort.TokenUsage parseUsage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return LLMPort.TokenUsage.empty();
        }
        long prompt = node.path("prompt_tokens").asLong(0L);
        long completion = node.path("completion_tokens").asLong(0L);
        long total = node.path("total_tokens").asLong(prompt + completion);
        return LLMPort.TokenUsage.of(prompt, completion, total);
    }
}
