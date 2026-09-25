package org.skylark.langur.infrastructure.harness.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.port.DecisionPort;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J1 验收 - {@link TypeSafeDecisionAdapter} 离线确定性单测（JDK HttpServer 桩，不依赖真实 Jev）。
 * <p>覆盖：choice/noul/score + confidence + distribution 正确解析、批量多问题一次往返（state 只发一次）、
 * Authorization 头注入且 api-key 不落请求体、后端异常/超时/不可达时委派 {@link RuleFallbackDecisionAdapter} 不抛出。</p>
 */
class TypeSafeDecisionAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<HttpServer> servers = new ArrayList<>();
    private final Stub stub = new Stub();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    private TypeSafeDecisionAdapter newAdapter(String apiKey, long timeoutMillis, DecisionPort fallback)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", stub);
        server.start();
        servers.add(server);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
        return new TypeSafeDecisionAdapter(baseUrl, "jev-latest", apiKey, timeoutMillis, MAPPER, fallback);
    }

    private static DecisionRequest batchRequest() {
        return DecisionRequest.of("合同正文含转账到陌生账户", "jev-1.13.0", List.of(
                DecisionQuestion.choice("route", "选择执行范式",
                        Map.of("react", "简单抽取", "plan", "复杂多步")),
                DecisionQuestion.probability("done", "任务是否已达成"),
                DecisionQuestion.score("risk", "评估合规风险")));
    }

    @Test
    void shouldParseChoiceNoulScoreWithConfidenceAndDistributionInOneRoundTrip() throws Exception {
        stub.response = """
                {"answers":{
                    "route":{"choice":"react","confidence":0.92,"distribution":{"react":0.92,"plan":0.08}},
                    "done":{"value":0.97,"confidence":0.95},
                    "risk":{"value":0.30,"confidence":0.88}},
                 "usage":{"prompt_tokens":120,"completion_tokens":0,"total_tokens":120}}""";
        TypeSafeDecisionAdapter adapter = newAdapter("test-key", 5000, new RuleFallbackDecisionAdapter());

        DecisionResponse response = adapter.decide(batchRequest());

        assertEquals(1, stub.hits.get(), "批量：三问题应一次往返");
        DecisionAnswer route = response.answer("route");
        assertEquals(DecisionType.CHOICE, route.type());
        assertEquals("react", route.choice());
        assertEquals(0.92d, route.confidence(), 1e-9);
        assertEquals(0.08d, route.distribution().get("plan"), 1e-9);

        DecisionAnswer done = response.answer("done");
        assertEquals(DecisionType.PROBABILITY, done.type());
        assertEquals(0.97d, done.value(), 1e-9);

        DecisionAnswer risk = response.answer("risk");
        assertEquals(DecisionType.SCORE, risk.type());
        assertEquals(0.30d, risk.value(), 1e-9);

        assertEquals(120L, response.usage().getTotalTokens());
        assertFalse(response.usage().isEmpty());
    }

    @Test
    void shouldSendStateOnceWithTypedQuestionFieldsAndAuthHeaderWithoutLeakingKey() throws Exception {
        stub.response = "{\"answers\":{\"route\":{\"choice\":\"plan\",\"confidence\":0.9}}}";
        TypeSafeDecisionAdapter adapter = newAdapter("s3cret-key", 5000, new RuleFallbackDecisionAdapter());

        adapter.decide(batchRequest());

        JsonNode body = MAPPER.readTree(stub.lastBody.get());
        assertEquals("jev-1.13.0", body.path("model").asText());
        assertEquals(1, countStateOccurrences(stub.lastBody.get()), "state 只发一次");
        JsonNode questions = body.path("questions");
        assertEquals(3, questions.size());
        assertTrue(questions.get(0).has("choice"), "CHOICE 问题落 choice 子对象");
        assertTrue(questions.get(1).has("noul"), "PROBABILITY 问题落 noul 子对象");
        assertTrue(questions.get(2).has("score"), "SCORE 问题落 score 子对象");
        assertEquals("简单抽取", questions.get(0).path("choice").path("criteria").path("react").asText());

        assertEquals("Bearer s3cret-key", stub.lastAuth.get(), "Authorization 头注入解析后的 key");
        assertFalse(stub.lastBody.get().contains("s3cret-key"), "api-key 绝不落请求体");
    }

    @Test
    void shouldOmitAuthHeaderWhenApiKeyBlankForLocalBackend() throws Exception {
        stub.response = "{\"answers\":{\"risk\":{\"value\":0.1,\"confidence\":0.9}}}";
        TypeSafeDecisionAdapter adapter = newAdapter("", 5000, new RuleFallbackDecisionAdapter());

        adapter.decide(DecisionRequest.of("code context", List.of(
                DecisionQuestion.score("risk", "评估风险"))));

        assertTrue(stub.lastAuth.get() == null || stub.lastAuth.get().isBlank(),
                "免鉴权（local 后端复用同适配器，apiKey 留空）");
    }

    @Test
    void shouldFallbackWithoutThrowingWhenBackendReturnsError() throws Exception {
        stub.status = 500;
        stub.response = "{\"error\":\"boom\"}";
        RecordingFallback fallback = new RecordingFallback();
        TypeSafeDecisionAdapter adapter = newAdapter("k", 5000, fallback);

        DecisionResponse response = adapter.decide(batchRequest());

        assertNotNull(response, "后端 5xx 不得抛出，应委派兜底");
        assertEquals(1, fallback.calls.get(), "应委派 RuleFallback 一次");
        assertEquals(3, response.answers().size(), "兜底为每个问题产出保守判定");
        assertEquals(0d, response.answer("route").confidence(), 1e-9, "兜底 confidence=0 → fail-closed");
    }

    @Test
    void shouldFallbackWithoutThrowingWhenBackendUnreachable() throws Exception {
        RecordingFallback fallback = new RecordingFallback();
        // 指向未监听端口 → 连接被拒
        TypeSafeDecisionAdapter adapter = new TypeSafeDecisionAdapter(
                "http://127.0.0.1:1/v1/systemone", "jev-latest", "k", 2000, MAPPER, fallback);

        DecisionResponse response = adapter.decide(batchRequest());

        assertNotNull(response);
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void shouldShortCircuitEmptyRequestWithoutBackendCall() throws Exception {
        stub.response = "{\"answers\":{}}";
        TypeSafeDecisionAdapter adapter = newAdapter("k", 5000, new RuleFallbackDecisionAdapter());

        DecisionResponse response = adapter.decide(DecisionRequest.of("state"));

        assertTrue(response.isEmpty());
        assertEquals(0, stub.hits.get(), "无问题不应发起后端往返");
    }

    private static int countStateOccurrences(String body) {
        int count = 0;
        int idx = body.indexOf("\"state\"");
        while (idx >= 0) {
            count++;
            idx = body.indexOf("\"state\"", idx + 1);
        }
        return count;
    }

    /** 记录型兜底桩：统计委派次数并返回保守判定。 */
    private static final class RecordingFallback implements DecisionPort {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls.incrementAndGet();
            return new RuleFallbackDecisionAdapter().decide(request);
        }
    }

    /** HttpServer 桩：记录请求体/头，按 status/response 回放。 */
    private static final class Stub implements com.sun.net.httpserver.HttpHandler {
        volatile String response = "{\"answers\":{}}";
        volatile int status = 200;
        final AtomicInteger hits = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> lastBody =
                new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicReference<String> lastAuth =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
