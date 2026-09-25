package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13.4 验收 - 错误传播修复：传输/HTTP 非 2xx/反序列化失败抛 {@link ModelProviderException}，
 * 使 {@code fallback-chains} 真正触发（修复前适配器吞异常返回 "Error: ..." 字符串，降级链形同虚设）。
 * <p>离线确定性：本地 JDK HttpServer 桩（可控响应）+ 127.0.0.1:9 拒绝连接，不依赖真实 LLM 端点。</p>
 */
class ModelProviderErrorPropagationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 启动本地桩服务：所有请求按给定状态码/响应体返回。 */
    private String stubServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private ConfigurableOpenAICompatibleLLMAdapter adapter(String baseUrl, String model, String... prefixes) {
        LlmProperties.ProviderProperties props = new LlmProperties.ProviderProperties();
        props.setBaseUrl(baseUrl);
        props.setModel(model);
        return new ConfigurableOpenAICompatibleLLMAdapter(
                "stub", props, null, List.of(prefixes), objectMapper);
    }

    @Test
    void shouldThrowModelProviderExceptionOnConnectionFailure() {
        // 127.0.0.1:9 = discard 端口，连接立即被拒绝（确定性离线）
        ConfigurableOpenAICompatibleLLMAdapter dead = adapter("http://127.0.0.1:9/v1", "gpt-4o", "gpt-");

        ModelProviderException decideEx = assertThrows(ModelProviderException.class,
                () -> dead.decide("sys", "gpt-4o", List.of(), List.of()));
        assertEquals("stub", decideEx.getProvider());
        assertThrows(ModelProviderException.class, () -> dead.complete("sys", "gpt-4o", "hi"));
        assertThrows(ModelProviderException.class,
                () -> dead.streamComplete("sys", "gpt-4o", "hi", token -> { }));
    }

    @Test
    void shouldThrowModelProviderExceptionOnHttp5xx() throws Exception {
        String baseUrl = stubServer(500, "{\"error\":\"boom\"}");
        ConfigurableOpenAICompatibleLLMAdapter failing = adapter(baseUrl, "gpt-4o", "gpt-");

        assertThrows(ModelProviderException.class, () -> failing.complete("sys", "gpt-4o", "hi"));
        assertThrows(ModelProviderException.class,
                () -> failing.decide("sys", "gpt-4o", List.of(), List.of()));
    }

    @Test
    void shouldThrowModelProviderExceptionOnMalformedResponse() throws Exception {
        String baseUrl = stubServer(200, "not-a-json");
        ConfigurableOpenAICompatibleLLMAdapter broken = adapter(baseUrl, "gpt-4o", "gpt-");

        assertThrows(ModelProviderException.class, () -> broken.decide("sys", "gpt-4o", List.of(), List.of()));
    }

    @Test
    void shouldNotTreatErrorTextInNormalContentAsFailure() throws Exception {
        // 边界：模型正常返回的内容里含 "error" 文本不算失败，只有传输层异常才 throw
        String baseUrl = stubServer(200,
                "{\"choices\":[{\"message\":{\"content\":\"there was an error in your input, please retry\"}}]}");
        ConfigurableOpenAICompatibleLLMAdapter ok = adapter(baseUrl, "gpt-4o", "gpt-");

        assertEquals("there was an error in your input, please retry", ok.complete("sys", "gpt-4o", "hi"));
    }

    @Test
    void shouldTriggerFallbackChainWhenPrimaryProviderTransportFails() throws Exception {
        String goodBaseUrl = stubServer(200,
                "{\"choices\":[{\"message\":{\"content\":\"backup-answer\"}}]}");
        // 主 provider 连接拒绝 → 抛 ModelProviderException；备用 provider 命中桩服务正常返回
        ConfigurableOpenAICompatibleLLMAdapter dead = adapter("http://127.0.0.1:9/v1", "bad-model", "bad-");
        ConfigurableOpenAICompatibleLLMAdapter good = adapter(goodBaseUrl, "good-model", "good-");

        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("stub");
        props.getRoleModels().put("REASONING", "bad-model");
        props.getFallbackChains().put("bad-model", List.of("good-model"));
        LLMRouter router = new LLMRouter(List.of(dead, good), props);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGateway gateway = new LlmGateway(router, props, registry);

        String result = gateway.complete(ModelRole.REASONING, "sys", "user");

        assertEquals("backup-answer", result, "主模型传输失败应实测降级到备用模型");
        assertEquals(1.0, registry.counter("langur.harness.llm_fallback_count",
                "role", "REASONING", "model", "bad-model",
                "error", "ModelProviderException").count(),
                "llm_fallback_count 修复前恒 0，修复后实测可触发");
    }

    @Test
    void shouldThrowIllegalStateWhenWholeChainTransportFails() {
        ConfigurableOpenAICompatibleLLMAdapter dead1 = adapter("http://127.0.0.1:9/v1", "bad-1", "bad-");
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("stub");
        props.getRoleModels().put("ACTION", "bad-1");
        props.getFallbackChains().put("bad-1", List.of("bad-2"));
        LlmGateway gateway = new LlmGateway(new LLMRouter(List.of(dead1), props), props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> gateway.complete(ModelRole.ACTION, "sys", "user"));
        assertTrue(ex.getMessage().contains("fallback chain exhausted"));
        assertTrue(ex.getCause() instanceof ModelProviderException, "链耗尽保留最后一次 provider 异常");
    }
}
