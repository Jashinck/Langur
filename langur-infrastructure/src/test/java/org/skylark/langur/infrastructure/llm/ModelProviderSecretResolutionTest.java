package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.harness.context.vector.EmbeddingProperties;
import org.skylark.langur.infrastructure.harness.context.vector.LlmEmbeddingPort;
import org.skylark.langur.infrastructure.harness.tool.rest.CompositeSecretResolver;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13.6 验收 - LLM API Key 经 {@link SecretResolver} 解析：{@code api-key-ref}（env:/prop:/kms:）
 * 优先于明文 {@code api-key}；解析结果只注入请求头绝不落日志；{@code kms:} 引用缺失 KMS 后端时
 * fail-closed 抛错（绝不静默回退明文）。
 * <p>离线确定性：本地 JDK HttpServer 桩捕获 Authorization 头，不依赖真实 LLM 端点。</p>
 */
class ModelProviderSecretResolutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelProviderFactory factory = new ModelProviderFactory();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 启动本地桩服务：捕获 Authorization 头并按给定状态码/响应体返回。 */
    private String stubServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

    private LlmProperties propsWithProvider(String baseUrl, String apiKey, String apiKeyRef) {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("stub");
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setEnabled(true);
        provider.setType(ModelProviderFactory.TYPE_OPENAI_COMPATIBLE);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setApiKeyRef(apiKeyRef);
        provider.setModel("gpt-4o");
        provider.setModelPrefixes(List.of("gpt-"));
        props.getProviders().put("stub", provider);
        return props;
    }

    private static SecretResolver mapResolver(Map<String, String> mapping) {
        return reference -> Optional.ofNullable(mapping.get(reference));
    }

    private ModelRoutableLLMPort stubAdapter(LlmProperties props, SecretResolver resolver) {
        return factory.create(props, objectMapper, resolver).stream()
                .filter(port -> "stub".equals(port.getProviderName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldInjectResolvedKeyIntoAuthorizationHeader() throws Exception {
        String baseUrl = stubServer(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        LlmProperties props = propsWithProvider(baseUrl, null, "env:STUB_LLM_KEY");

        ModelRoutableLLMPort adapter = stubAdapter(props, mapResolver(Map.of("env:STUB_LLM_KEY", "secret-123")));
        adapter.complete("sys", "gpt-4o", "hi");

        assertEquals("Bearer secret-123", capturedAuth.get(), "api-key-ref 解析结果应注入请求头");
    }

    @Test
    void shouldPreferApiKeyRefOverPlainApiKey() throws Exception {
        String baseUrl = stubServer(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        LlmProperties props = propsWithProvider(baseUrl, "plain-key", "prop:stub.key");

        ModelRoutableLLMPort adapter = stubAdapter(props, mapResolver(Map.of("prop:stub.key", "resolved-key")));
        adapter.complete("sys", "gpt-4o", "hi");

        assertEquals("Bearer resolved-key", capturedAuth.get(), "ref 解析成功时优先于明文 api-key");
    }

    @Test
    void shouldFallbackToPlainKeyWhenRefUnresolvable() throws Exception {
        String baseUrl = stubServer(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        LlmProperties props = propsWithProvider(baseUrl, "plain-key", "env:NOT_SET");

        ModelRoutableLLMPort adapter = stubAdapter(props, reference -> Optional.empty());
        adapter.complete("sys", "gpt-4o", "hi");

        assertEquals("Bearer plain-key", capturedAuth.get(), "ref 解析为空时回退明文 api-key（向后兼容）");
    }

    @Test
    void shouldFailClosedWhenKmsReferenceWithoutKmsBackend() {
        LlmProperties props = propsWithProvider("http://127.0.0.1:9/v1", "plain-key", "kms:cipher-text");
        CompositeSecretResolver noKms =
                new CompositeSecretResolver((org.skylark.langur.infrastructure.harness.tool.rest.KmsClient) null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.create(props, objectMapper, noKms));
        assertTrue(ex.getMessage().contains("kms:"), "kms 引用缺失后端必须 fail-closed，绝不静默回退明文");
    }

    @Test
    void shouldNotLeakResolvedKeyInExceptionMessage() throws Exception {
        String baseUrl = stubServer(500, "{\"error\":\"boom\"}");
        LlmProperties props = propsWithProvider(baseUrl, null, "env:STUB_LLM_KEY");

        ModelRoutableLLMPort adapter = stubAdapter(props, mapResolver(Map.of("env:STUB_LLM_KEY", "secret-123")));
        ModelProviderException ex = assertThrows(ModelProviderException.class,
                () -> adapter.complete("sys", "gpt-4o", "hi"));

        assertFalse(String.valueOf(ex.getMessage()).contains("secret-123"), "异常消息不得携带密钥明文");
        assertFalse(String.valueOf(ex.getCause()).contains("secret-123"));
    }

    @Test
    void shouldResolveApiKeyRefForEmbeddingPort() throws Exception {
        // LlmEmbeddingPort 同链路（H13.6）：默认 provider 的 api-key-ref 经 SecretResolver 解析后注入
        LlmProperties llmProps = propsWithProvider("http://127.0.0.1:9/v1", "plain-key", "env:STUB_EMBED_KEY");
        EmbeddingProperties embeddingProps = new EmbeddingProperties();
        embeddingProps.setType("llm");
        embeddingProps.setModel("text-embedding-3-small");
        LlmGateway gateway = new LlmGateway(new NoopPort(), llmProps);

        LlmEmbeddingPort port = new LlmEmbeddingPort(embeddingProps, llmProps, gateway, objectMapper,
                new SingleValueProvider<>(mapResolver(Map.of("env:STUB_EMBED_KEY", "embed-secret"))));

        Field apiKeyField = LlmEmbeddingPort.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);
        assertEquals("embed-secret", apiKeyField.get(port), "embedding 端口应使用 ref 解析后的密钥");
    }

    /** 最小 ObjectProvider：仅 getIfAvailable 有效，其余方法测试不涉及。 */
    private static class SingleValueProvider<T> implements ObjectProvider<T> {
        private final T value;

        SingleValueProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }
    }

    /** 占位 LLMPort：LlmGateway 构造需要，本测试不触发任何调用。 */
    private static class NoopPort implements LLMPort {
        @Override
        public LLMDecision decide(String systemPrompt, String model,
                                  List<Map<String, String>> history, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String complete(String systemPrompt, String model, String userMessage) {
            throw new UnsupportedOperationException();
        }
    }
}
