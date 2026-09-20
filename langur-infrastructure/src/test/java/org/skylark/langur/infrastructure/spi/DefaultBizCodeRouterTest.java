package org.skylark.langur.infrastructure.spi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.common.spi.BizContext;
import org.skylark.langur.common.spi.BusinessExecutorSPI;
import org.skylark.langur.common.spi.ContextEnricherSPI;
import org.skylark.langur.common.spi.DecisionEngineSPI;
import org.skylark.langur.common.spi.OutputPostProcessorSPI;
import org.skylark.langur.common.spi.PromptTemplateSPI;
import org.skylark.langur.common.spi.SecurityPolicySPI;
import org.skylark.langur.common.spi.SpiToolSpec;
import org.skylark.langur.common.spi.ToolProviderSPI;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T9 验收：七大 SPI + BizCodeRouter 路由（示例业务域 bizCode=demo 跑通）。
 */
class DefaultBizCodeRouterTest {

    // ===== 示例业务域 demo 的 SPI 实现 =====

    static class DemoToolProvider implements ToolProviderSPI {
        @Override public String getBizCode() { return "demo"; }
        @Override public List<SpiToolSpec> getTools() {
            return List.of(SpiToolSpec.builder()
                    .toolId("demo-echo").description("echo back")
                    .inputSchema(Map.of("type", "object"))
                    .permission("demo.read").riskLevel("LOW").build());
        }
        @Override public String execute(String toolId, Map<String, Object> args, BizContext ctx) {
            return "demo:" + toolId + ":" + args.get("msg");
        }
    }

    static class DefaultToolProvider implements ToolProviderSPI {
        @Override public String getBizCode() { return "default"; }
        @Override public List<SpiToolSpec> getTools() { return List.of(); }
        @Override public String execute(String toolId, Map<String, Object> args, BizContext ctx) { return "default"; }
    }

    static class DemoPromptTemplate implements PromptTemplateSPI {
        @Override public String getBizCode() { return "demo"; }
        @Override public String buildPrompt(BizContext ctx) { return "You are DEMO agent for " + ctx.getUserMessage(); }
    }

    static class DemoExecutor implements BusinessExecutorSPI {
        @Override public String getBizCode() { return "demo"; }
        @Override public Object execute(BizContext ctx) { return "executed-" + ctx.getBizCode(); }
    }

    static class DemoSecurityPolicy implements SecurityPolicySPI {
        @Override public String getBizCode() { return "demo"; }
        @Override public boolean isAllowed(String action, BizContext ctx) { return !"forbidden".equals(action); }
    }

    static class DemoDecisionEngine implements DecisionEngineSPI {
        @Override public String getBizCode() { return "demo"; }
        @Override public Optional<String> decide(BizContext ctx) { return Optional.of("PLAN_AND_EXECUTE"); }
    }

    static class TaggingEnricher implements ContextEnricherSPI {
        private final int order; private final String key; private final Object val;
        TaggingEnricher(int order, String key, Object val) { this.order = order; this.key = key; this.val = val; }
        @Override public int getOrder() { return order; }
        @Override public void enrich(BizContext ctx) { ctx.putAttribute(key, val); }
    }

    static class SuffixPostProcessor implements OutputPostProcessorSPI {
        private final int order; private final String suffix;
        SuffixPostProcessor(int order, String suffix) { this.order = order; this.suffix = suffix; }
        @Override public int getOrder() { return order; }
        @Override public String process(String output, BizContext ctx) { return output + suffix; }
    }

    private DefaultBizCodeRouter router() {
        return new DefaultBizCodeRouter(
                List.of(new DemoExecutor()),
                List.of(new DemoPromptTemplate()),
                List.of(new DemoToolProvider(), new DefaultToolProvider()),
                List.of(new DemoSecurityPolicy()),
                List.of(new DemoDecisionEngine()),
                List.of(new TaggingEnricher(20, "second", 2), new TaggingEnricher(10, "first", 1)),
                List.of(new SuffixPostProcessor(20, "[b]"), new SuffixPostProcessor(10, "[a]")));
    }

    @Test
    void shouldResolveDemoToolsAndExecute() {
        ToolProviderSPI provider = router().toolProvider("demo").orElseThrow();
        assertEquals(1, provider.getTools().size());
        assertEquals("demo-echo", provider.getTools().get(0).getToolId());
        String result = provider.execute("demo-echo", Map.of("msg", "hi"), BizContext.of("demo"));
        assertEquals("demo:demo-echo:hi", result);
    }

    @Test
    void shouldResolveDemoPromptAndExecutorAndDecision() {
        DefaultBizCodeRouter router = router();
        assertEquals("You are DEMO agent for q",
                router.promptTemplate("demo").orElseThrow().buildPrompt(
                        BizContext.builder().bizCode("demo").userMessage("q").build()));
        assertEquals("executed-demo", router.executor("demo").orElseThrow().execute(BizContext.of("demo")));
        assertEquals(Optional.of("PLAN_AND_EXECUTE"), router.decisionEngine("demo").orElseThrow().decide(BizContext.of("demo")));
    }

    @Test
    void shouldApplySecurityPolicy() {
        SecurityPolicySPI policy = router().securityPolicy("demo").orElseThrow();
        assertTrue(policy.isAllowed("read", BizContext.of("demo")));
        assertFalse(policy.isAllowed("forbidden", BizContext.of("demo")));
        assertTrue(policy.denyReason("forbidden", BizContext.of("demo")).contains("denied"));
    }

    @Test
    void shouldFallbackToDefaultWhenBizCodeUnknown() {
        DefaultBizCodeRouter router = router();
        // 未注册的 bizCode 回退 default 工具提供者
        assertTrue(router.toolProvider("unknown").isPresent());
        assertEquals("default", router.toolProvider("unknown").orElseThrow()
                .execute("x", Map.of(), BizContext.of("unknown")));
        // 无 default 实现的扩展点返回空
        assertTrue(router.promptTemplate("unknown").isEmpty());
        assertTrue(router.executor("unknown").isEmpty());
    }

    @Test
    void shouldOrderEnrichersAndPostProcessors() {
        DefaultBizCodeRouter router = router();
        BizContext ctx = BizContext.of("demo");
        router.contextEnrichers().forEach(e -> e.enrich(ctx));
        assertEquals(1, ctx.attribute("first"));
        assertEquals(2, ctx.attribute("second"));
        // 增强器按 order 升序：first(10) 先于 second(20)
        assertEquals(10, router.contextEnrichers().get(0).getOrder());

        String out = "x";
        for (OutputPostProcessorSPI p : router.outputPostProcessors()) {
            out = p.process(out, ctx);
        }
        assertEquals("x[a][b]", out);
    }
}
