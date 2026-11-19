package org.skylark.langur.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * H13.2/H13.3 验收 - GLM 纯配置接入 + 可配置 model-prefixes。
 * <p>H13.2：{@code glm-4-plus} 经工厂+路由命中，零 Java 代码（验证 H13.1 "零代码扩厂"）；
 * H13.3：改 YAML 前缀即改路由；多前缀（glm-/chatglm-）生效；未配置回退 provider.model 派生前缀。</p>
 * <p>离线确定性：本地 JDK HttpServer 桩，不依赖真实 LLM 端点。</p>
 */
class ModelPrefixRoutingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelProviderFactory factory = new ModelProviderFactory();
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
    }

    /** 启动一个本地桩服务：返回固定 OpenAI 格式响应（content 可辨识路由命中方）。 */
    private String stub(String content) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = ("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
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

    private LlmProperties.ProviderProperties provider(String baseUrl, String model, String... prefixes) {
        LlmProperties.ProviderProperties props = new LlmProperties.ProviderProperties();
        props.setEnabled(true);
        props.setType(ModelProviderFactory.TYPE_OPENAI_COMPATIBLE);
        props.setBaseUrl(baseUrl);
        props.setModel(model);
        props.setModelPrefixes(List.of(prefixes));
        return props;
    }

    @Test
    void shouldRouteGlmModelViaPureConfigWithoutJavaCode() throws Exception {
        String glmBase = stub("glm-answer");
        String openaiBase = stub("openai-answer");
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("openai");
        props.getProviders().put("glm", provider(glmBase, "glm-4-plus", "glm-", "chatglm-"));
        props.getProviders().put("openai", provider(openaiBase, "gpt-4o", "gpt-"));
        LLMRouter router = new LLMRouter(factory.create(props, objectMapper), props);

        assertEquals("glm-answer", router.complete("sys", "glm-4-plus", "hi"), "H13.2：glm-4-plus 纯配置路由命中");
        assertEquals("glm-answer", router.complete("sys", "chatglm-6", "hi"), "H13.3：多前缀 chatglm- 生效");
        assertEquals("openai-answer", router.complete("sys", "gpt-4o", "hi"), "既有 openai 路由不受影响");
        assertEquals("openai-answer", router.complete("sys", "mystery-model", "hi"), "无命中回退 default-provider");
    }

    @Test
    void shouldSupportAllConfiguredPrefixes() throws Exception {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("glm");
        props.getProviders().put("glm", provider(stub("glm-answer"), "glm-4-plus", "glm-", "chatglm-"));

        ModelRoutableLLMPort glm = factory.create(props, objectMapper).stream()
                .filter(port -> "glm".equals(port.getProviderName()))
                .findFirst()
                .orElseThrow();

        assertTrue(glm.supportsModel("glm-4-plus"));
        assertTrue(glm.supportsModel("chatglm-6"));
        assertFalse(glm.supportsModel("gpt-4o"), "非配置前缀不得命中");
    }

    @Test
    void shouldChangeRoutingWhenPrefixesOverriddenInConfig() throws Exception {
        // H13.3：provider.model 为 gpt-4o 但 YAML 前缀改为 custom- → 路由随配置改变，无需改码
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("custom");
        props.getProviders().put("custom", provider(stub("custom-answer"), "gpt-4o", "custom-"));

        ModelRoutableLLMPort custom = factory.create(props, objectMapper).stream()
                .filter(port -> "custom".equals(port.getProviderName()))
                .findFirst()
                .orElseThrow();

        assertTrue(custom.supportsModel("custom-1"), "配置前缀优先");
        assertFalse(custom.supportsModel("gpt-4o"), "配置前缀存在时不再按 provider.model 派生");
    }

    @Test
    void shouldDerivePrefixFromProviderModelWhenPrefixesUnset() throws Exception {
        // H13.3：未配置前缀 → 回退按 provider.model 首个 '-' 前缀派生（gpt-4o → gpt-）
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("myllm");
        LlmProperties.ProviderProperties bare = provider(stub("my-answer"), "gpt-4o");
        bare.setModelPrefixes(List.of());
        props.getProviders().put("myllm", bare);

        ModelRoutableLLMPort myllm = factory.create(props, objectMapper).stream()
                .filter(port -> "myllm".equals(port.getProviderName()))
                .findFirst()
                .orElseThrow();

        assertTrue(myllm.supportsModel("gpt-4o-mini"));
        assertTrue(myllm.supportsModel("gpt-4o"));
        assertFalse(myllm.supportsModel("qwen-max"));
    }
}
