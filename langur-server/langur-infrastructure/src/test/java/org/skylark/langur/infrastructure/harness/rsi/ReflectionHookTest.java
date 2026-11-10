package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 反思自检 Hook 离线确定性测试（无 Mockito，匿名桩实现端口/网关）。
 * <p>覆盖：初筛高分高置信跳过 M5、初筛低质升级 M5 改写、M5 改写后 MODIFY、
 * 无改动返回 CONTINUE、H10 之前 order、总开关/分开关关则 enabled=false、
 * 决策平面缺失/异常一律降级（P10）、M5 失败降级放行。</p>
 */
class ReflectionHookTest {

    private final RsiProperties props = props(true, true, 0.85);

    private static RsiProperties props(boolean master, boolean reflection, double threshold) {
        RsiProperties p = new RsiProperties();
        p.setEnabled(master);
        p.getReflection().setEnabled(reflection);
        p.getReflection().setPrescreenThreshold(threshold);
        return p;
    }

    /** 可替换的 DecisionPort 桩：测试用 lambda/置空控制各分支。 */
    private volatile DecisionPort currentPort;

    private ObjectProvider<DecisionPort> portProvider() {
        return new ObjectProvider<DecisionPort>() {
            @Override
            public DecisionPort getObject() {
                return currentPort;
            }

            @Override
            public DecisionPort getObject(Object... args) {
                return currentPort;
            }

            @Override
            public DecisionPort getIfAvailable() {
                return currentPort;
            }

            @Override
            public DecisionPort getIfUnique() {
                return currentPort;
            }
        };
    }

    private ReflectionHook hook(LlmGateway gateway) {
        return new ReflectionHook(portProvider(), gateway, props);
    }

    private LlmGateway refactorGateway() {
        return new LlmGateway(null, null) {
            @Override
            public String complete(ModelRole role, String systemPrompt, String userMessage) {
                return "REFACTORED:" + userMessage;
            }
        };
    }

    private LlmGateway refactorGateway(AtomicInteger counter, String result) {
        return new LlmGateway(null, null) {
            @Override
            public String complete(ModelRole role, String systemPrompt, String userMessage) {
                counter.incrementAndGet();
                return result;
            }
        };
    }

    private LlmGateway failingGateway() {
        return new LlmGateway(null, null) {
            @Override
            public String complete(ModelRole role, String systemPrompt, String userMessage) {
                throw new IllegalStateException("llm down");
            }
        };
    }

    private static DecisionPort scorePort(double value, double confidence) {
        return req -> DecisionResponse.of(Map.of("answer-quality", DecisionAnswer.ofScore(value, confidence)));
    }

    @Test
    void shouldPassHighQualityPreScreenWithoutM5() {
        AtomicInteger m5Calls = new AtomicInteger();
        currentPort = scorePort(0.95, 0.9);
        ReflectionHook hook = hook(refactorGateway(m5Calls, "REFACTORED:good answer"));

        HookResult result = hook.execute(HookContext.of(HookPoint.BEFORE_OUTPUT, "t1", "good answer"));

        assertEquals("CONTINUE", result.getAction().name());
        assertEquals(0, m5Calls.get(), "高质高置信初筛应跳过 M5");
    }

    @Test
    void shouldEscalateLowQualityToM5AndModify() {
        AtomicInteger preScreenCalls = new AtomicInteger();
        AtomicInteger m5Calls = new AtomicInteger();
        currentPort = req -> {
            preScreenCalls.incrementAndGet();
            return DecisionResponse.of(Map.of("answer-quality", DecisionAnswer.ofScore(0.3, 0.8)));
        };
        ReflectionHook hook = hook(refactorGateway(m5Calls, "REFACTORED:lousy draft"));

        HookResult result = hook.execute(HookContext.of(HookPoint.BEFORE_OUTPUT, "t2", "lousy draft"));

        assertEquals("MODIFY", result.getAction().name());
        assertEquals("REFACTORED:lousy draft", result.getModifiedPayload());
        assertEquals(1, m5Calls.get());
        assertEquals(1, preScreenCalls.get());
    }

    @Test
    void shouldReturnContinueWhenM5ReturnsUnchanged() {
        currentPort = scorePort(0.3, 0.8);
        ReflectionHook hook = hook(refactorGateway(new AtomicInteger(), "lousy draft"));

        HookResult result = hook.execute(HookContext.of(HookPoint.BEFORE_OUTPUT, "t3", "lousy draft"));

        assertEquals("CONTINUE", result.getAction().name());
    }

    @Test
    void orderShouldPrecedeH10ContentReview() {
        // H10 ContentReviewOutputHook.order()=10；反思必须在其之前（order=5）
        assertTrue(hook(refactorGateway()).order() < 10);
        assertEquals(HookPoint.BEFORE_OUTPUT, hook(refactorGateway()).point());
    }

    @Test
    void disabledWhenMasterOrReflectionOff() {
        ObjectProvider<DecisionPort> provider = portProvider();
        assertFalse(new ReflectionHook(provider, refactorGateway(), props(true, false, 0.85)).enabled());
        assertFalse(new ReflectionHook(provider, refactorGateway(), props(false, true, 0.85)).enabled());
        assertTrue(new ReflectionHook(provider, refactorGateway(), props(true, true, 0.85)).enabled());
    }

    @Test
    void degradeToM5WhenDecisionPortMissing() {
        currentPort = null;
        HookResult result = hook(refactorGateway()).execute(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "t4", "draft"));
        // 缺端口 → 初筛不可能通过 → 升级 M5 → 网关桩改写
        assertEquals("MODIFY", result.getAction().name());
    }

    @Test
    void degradeToM5WhenDecisionPortThrows() {
        currentPort = req -> {
            throw new IllegalStateException("backend down");
        };
        HookResult result = hook(refactorGateway()).execute(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "t5", "draft"));
        assertEquals("MODIFY", result.getAction().name(), "P10：初筛异常 → 升级 M5");
    }

    @Test
    void degradeToOriginalWhenM5Fails() {
        currentPort = scorePort(0.3, 0.8); // 低质 → 升级 M5 → M5 失败
        ReflectionHook hook = hook(failingGateway());

        HookResult result = hook.execute(HookContext.of(HookPoint.BEFORE_OUTPUT, "t6", "draft"));

        assertEquals("CONTINUE", result.getAction().name(), "M5 失败应降级放行原始答案");
    }

    @Test
    void ignoreBlankDraft() {
        currentPort = scorePort(0.95, 0.9);
        HookResult result = hook(refactorGateway()).execute(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "t7", "  "));
        assertEquals("CONTINUE", result.getAction().name());
    }

    @Test
    void shouldNotConsiderLowQualityButLowConfidenceAsToast() {
        // 低置信初筛不应被当作高质放行 → 升级 M5
        AtomicInteger m5Calls = new AtomicInteger();
        currentPort = scorePort(0.95, 0.4); // 值高但置信不足
        ReflectionHook hook = hook(refactorGateway(m5Calls, "REFACTORED:x"));

        HookResult result = hook.execute(HookContext.of(HookPoint.BEFORE_OUTPUT, "t8", "answer"));

        assertEquals("MODIFY", result.getAction().name());
        assertEquals(1, m5Calls.get());
        assertNotNull(result.getModifiedPayload());
    }
}
