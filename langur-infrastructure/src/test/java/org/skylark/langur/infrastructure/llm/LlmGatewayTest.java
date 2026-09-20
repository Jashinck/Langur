package org.skylark.langur.infrastructure.llm;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T15 验收：角色→模型映射配置化；主模型不可用时自动降级到备用模型；全链失败方抛出。
 */
class LlmGatewayTest {

    /** 名字以 "bad" 开头的模型抛异常，其余成功回显模型名。 */
    static class FakePort implements LLMPort {
        final List<String> calledModels = new ArrayList<>();

        @Override
        public LLMDecision decide(String systemPrompt, String model,
                                  List<Map<String, String>> history, List<Tool> tools) {
            calledModels.add(model);
            if (model.startsWith("bad")) {
                throw new RuntimeException("model down: " + model);
            }
            return LLMDecision.finalAnswer("decided:" + model);
        }

        @Override
        public String complete(String systemPrompt, String model, String userMessage) {
            calledModels.add(model);
            if (model.startsWith("bad")) {
                throw new RuntimeException("model down: " + model);
            }
            return "ok:" + model;
        }
    }

    private LlmProperties baseProperties() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("openai");
        LlmProperties.ProviderProperties openai = new LlmProperties.ProviderProperties();
        openai.setModel("gpt-4o");
        props.getProviders().put("openai", openai);
        return props;
    }

    @Test
    void shouldResolveModelByRoleAndFallbackToProviderDefault() {
        LlmProperties props = baseProperties();
        props.getRoleModels().put("REASONING", "reasoning-model");
        LlmGateway gateway = new LlmGateway(new FakePort(), props);

        assertEquals("reasoning-model", gateway.resolveModel(ModelRole.REASONING));
        // 未配置的角色回退到默认 provider 模型
        assertEquals("gpt-4o", gateway.resolveModel(ModelRole.ACTION));
    }

    @Test
    void shouldDegradeToBackupModelWhenPrimaryUnavailable() {
        LlmProperties props = baseProperties();
        props.getRoleModels().put("REASONING", "bad-primary");
        props.getFallbackChains().put("bad-primary", List.of("good-backup"));
        FakePort port = new FakePort();
        LlmGateway gateway = new LlmGateway(port, props);

        String result = gateway.complete(ModelRole.REASONING, "sys", "user");

        assertEquals("ok:good-backup", result);
        assertEquals(List.of("bad-primary", "good-backup"), port.calledModels);
    }

    @Test
    void shouldThrowWhenWholeFallbackChainExhausted() {
        LlmProperties props = baseProperties();
        props.getRoleModels().put("ACTION", "bad-1");
        props.getFallbackChains().put("bad-1", List.of("bad-2"));
        LlmGateway gateway = new LlmGateway(new FakePort(), props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> gateway.complete(ModelRole.ACTION, "sys", "user"));
        assertTrue(ex.getMessage().contains("fallback chain exhausted"));
    }

    @Test
    void shouldDegradeForDecideAndStream() {
        LlmProperties props = baseProperties();
        props.getRoleModels().put("REASONING", "bad-primary");
        props.getFallbackChains().put("bad-primary", List.of("good-backup"));
        FakePort port = new FakePort();
        LlmGateway gateway = new LlmGateway(port, props);

        LLMPort.LLMDecision decision = gateway.decide(ModelRole.REASONING, "sys", List.of(), List.of());
        assertEquals("decided:good-backup", decision.getFinalAnswer());

        StringBuilder sb = new StringBuilder();
        gateway.streamComplete(ModelRole.REASONING, "sys", "user", sb::append);
        // 流式默认降级为一次性产出（FakePort 未覆写 streamComplete）
        assertTrue(sb.length() > 0);
    }
}
