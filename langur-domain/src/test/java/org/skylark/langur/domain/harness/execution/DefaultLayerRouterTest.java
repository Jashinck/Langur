package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T6 验收 - LayerRouter 默认实现的规则路由与范式映射。
 */
class DefaultLayerRouterTest {

    private final DefaultLayerRouter router = new DefaultLayerRouter();

    @Test
    void shouldDefaultToReactWhenUnspecified() {
        assertEquals(RuntimeLayer.REACT_LAYER, router.route(null, null));
        assertEquals(RuntimeLayer.REACT_LAYER, router.route("default", "今天天气怎么样"));
        assertEquals(RuntimeParadigm.REACT, router.paradigmOf(router.route("default", "hi")));
    }

    @Test
    void shouldRouteWorkflowForComplianceBizCode() {
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("approval-flow", "任意消息"));
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("COMPLIANCE", "任意消息"));
        assertEquals(RuntimeParadigm.WORKFLOW, router.paradigmOf(router.route("workflow", "x")));
    }

    @Test
    void shouldRoutePlanForMultiStepTaskFeature() {
        assertEquals(RuntimeLayer.PLAN_LAYER, router.route("default", "请制定一个 plan 并分步执行"));
        assertEquals(RuntimeLayer.PLAN_LAYER, router.route("default", "帮我规划一次多步骤的旅行"));
        assertEquals(RuntimeParadigm.PLAN_AND_EXECUTE, router.paradigmOf(router.route("default", "step by step")));
    }
}
