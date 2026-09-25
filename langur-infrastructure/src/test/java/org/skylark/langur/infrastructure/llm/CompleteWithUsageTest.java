package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13.5 验收（适配器/网关侧）- OpenAI 兼容适配器 {@code completeWithUsage} 解析响应 usage
 * （复用 H1 {@code parseUsage}）；provider 未回传时为空计量；经 {@code LLMRouter}+{@code LlmGateway}
 * 降级链透传真实计量（补全路径纳入 H1 real/estimated 区分）。
 * <p>离线确定性：本地 JDK HttpServer 桩 + 127.0.0.1:9 拒绝连接，不依赖真实 LLM 端点。</p>
 */
class CompleteWithUsageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
    }

    private String stubServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        servers.add(server);
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
    void shouldReturnRealUsageFromCompletionResponse() throws Exception {
        String baseUrl = stubServer("{\"choices\":[{\"message\":{\"content\":\"hello\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}");

        LLMPort.CompletionResult result = adapter(baseUrl, "gpt-4o", "gpt-")
                .completeWithUsage("sys", "gpt-4o", "hi");

        assertEquals("hello", result.getContent());
        assertFalse(result.getUsage().isEmpty(), "provider 回传 usage 时应为真实计量");
        assertEquals(11, result.getUsage().getPromptTokens());
        assertEquals(7, result.getUsage().getCompletionTokens());
        assertEquals(18, result.getUsage().getTotalTokens());
    }

    @Test
    void shouldReturnEmptyUsageWhenProviderOmitsUsage() throws Exception {
        String baseUrl = stubServer("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");

        LLMPort.CompletionResult result = adapter(baseUrl, "gpt-4o", "gpt-")
                .completeWithUsage("sys", "gpt-4o", "hi");

        assertEquals("hi", result.getContent());
        assertTrue(result.getUsage().isEmpty(), "provider 未回传 usage 时为空计量，由调用方降级估算");
    }

    @Test
    void shouldKeepLegacyCompleteBehavior() throws Exception {
        String baseUrl = stubServer("{\"choices\":[{\"message\":{\"content\":\"legacy\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");

        assertEquals("legacy", adapter(baseUrl, "gpt-4o", "gpt-").complete("sys", "gpt-4o", "hi"),
                "旧 complete() 契约不变（回归）");
    }

    @Test
    void shouldPropagateUsageThroughGatewayFallbackChain() throws Exception {
        String goodBaseUrl = stubServer("{\"choices\":[{\"message\":{\"content\":\"backup-answer\"}}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}");
        ConfigurableOpenAICompatibleLLMAdapter dead = adapter("http://127.0.0.1:9/v1", "bad-model", "bad-");
        ConfigurableOpenAICompatibleLLMAdapter good = adapter(goodBaseUrl, "good-model", "good-");

        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("stub");
        props.getRoleModels().put("REASONING", "bad-model");
        props.getFallbackChains().put("bad-model", List.of("good-model"));
        LLMRouter router = new LLMRouter(List.of(dead, good), props);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGateway gateway = new LlmGateway(router, props, registry);

        LLMPort.CompletionResult result = gateway.completeWithUsage(ModelRole.REASONING, "sys", "user");

        assertEquals("backup-answer", result.getContent(), "主模型失败降级到备用模型");
        assertEquals(25, result.getUsage().getTotalTokens(), "降级后仍透传备用模型的真实计量");
        assertEquals(1.0, registry.counter("langur.harness.llm_fallback_count",
                "role", "REASONING", "model", "bad-model",
                "error", "ModelProviderException").count());
    }
}
