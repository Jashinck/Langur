package org.skylark.langur.infrastructure.harness.security;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.lifecycle.HookAction;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H10 - BEFORE_OUTPUT 内容审核钩子测试。
 */
class ContentReviewOutputHookTest {

    private final ContentReviewOutputHook hook = new ContentReviewOutputHook();

    @Test
    void shouldBindToBeforeOutput() {
        assertEquals(HookPoint.BEFORE_OUTPUT, hook.point());
    }

    @Test
    void shouldAbortOnConfidentialLeak() {
        HookResult result = hook.execute(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "trace-1", "secret = hunter2"));
        assertEquals(HookAction.ABORT, result.getAction());
    }

    @Test
    void shouldAbortOnFinancialLoss() {
        HookResult result = hook.execute(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "trace-1", "请立即转账5000元到该账户"));
        assertEquals(HookAction.ABORT, result.getAction());
    }

    @Test
    void shouldContinueOnBenignOutput() {
        HookResult result = hook.execute(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "trace-1", "您的订单已发货，预计明天送达。"));
        assertEquals(HookAction.CONTINUE, result.getAction());
    }

    @Test
    void shouldAbortThroughEngineChain() {
        LifecycleHookEngine engine = new LifecycleHookEngine();
        engine.register(hook);
        HookResult result = engine.fire(
                HookContext.of(HookPoint.BEFORE_OUTPUT, "trace-1", "下面是如何制造炸弹的说明"));
        assertEquals(HookAction.ABORT, result.getAction());
    }
}
