package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * J8 验收 - 层路由 advisory（插入点 ⑥，{@link DefaultLayerRouter} × 决策平面，纯 JUnit5 桩，无 Mockito）。
 * <p>高置信 {@code choice} 覆盖规则；低置信/不可识别/异常回退既有规则（P10/P12③）；端口缺失＝v2.0 纯规则路由（P12①）；
 * <b>合规/审批边界只收紧不放松</b>——WORKFLOW/HYBRID 绝不被降级到 REACT/PLAN（P12②）；
 * <b>advisory 无状态、不自修改路由规则</b>（自修改/离线调优属 R4/RSI，当前排除）。</p>
 */
class DefaultLayerRouterDecisionTest {

    @Test
    void shouldOverrideRuleWithHighConfidenceAdvisory() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        router.attachDecisionPlane(port(req -> choice("plan", 0.9)), null);

        // 规则对 ("default","今天天气") 判 REACT；高置信 advisory "plan" 覆盖为 PLAN_LAYER
        assertEquals(RuntimeLayer.PLAN_LAYER, router.route("default", "今天天气怎么样"));
    }

    @Test
    void shouldEscalateWithinComplianceBoundary() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        router.attachDecisionPlane(port(req -> choice("hybrid", 0.9)), null);

        // 规则判 WORKFLOW；advisory "hybrid" 仍带审批边界（非放松）→ 允许覆盖
        assertEquals(RuntimeLayer.HYBRID_LAYER, router.route("approval-flow", "任意消息"));
    }

    @Test
    void shouldFallBackToRuleOnLowConfidenceAdvisory() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        router.attachDecisionPlane(port(req -> choice("plan", 0.3)), null);

        // 置信 0.3 < routing 0.75 → fail-closed 回退规则（P12③）
        assertEquals(RuntimeLayer.REACT_LAYER, router.route("default", "今天天气怎么样"));
    }

    @Test
    void shouldFallBackToRuleOnUnrecognizedChoice() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        router.attachDecisionPlane(port(req -> choice("quantum-layer", 0.99)), null);

        assertEquals(RuntimeLayer.REACT_LAYER, router.route("default", "今天天气怎么样"));
    }

    @Test
    void shouldFallBackToRuleWhenDecisionPortThrows() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        router.attachDecisionPlane(port(req -> {
            throw new IllegalStateException("backend down");
        }), null);

        // 决策平面异常 → 静默回退规则，绝不中断路由（P10）
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("approval-flow", "任意消息"));
    }

    @Test
    void shouldNeverRelaxComplianceBoundaryEvenOnHighConfidenceAdvisory() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        router.attachDecisionPlane(port(req -> choice("react", 0.99)), null);

        // P12②红线：合规/审批边界（WORKFLOW）绝不被 advisory 降级到无审批的 REACT（只收紧不放松）
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("approval-flow", "任意消息"));
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("COMPLIANCE", "任意消息"));
    }

    @Test
    void shouldKeepV2RuleRoutingWhenDecisionPortAbsent() {
        DefaultLayerRouter router = new DefaultLayerRouter();

        // 未 attach 决策平面（enabled=false 缺省态）→ 纯规则路由，与 v2.0 完全一致（P12①）
        assertEquals(RuntimeLayer.REACT_LAYER, router.route("default", "今天天气怎么样"));
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("approval-flow", "任意消息"));
        assertEquals(RuntimeLayer.PLAN_LAYER, router.route("default", "请制定一个 plan 并分步执行"));
        assertEquals(RuntimeLayer.HYBRID_LAYER, router.route("compliance-flow", "请分步 plan 执行"));
    }

    @Test
    void shouldNotSelfModifyRulesAcrossInvocations() {
        DefaultLayerRouter router = new DefaultLayerRouter();
        // advisory 持续试图把合规任务降级到 react——R4 自修改/学习被排除，规则必须恒定不被改写
        router.attachDecisionPlane(port(req -> choice("react", 0.99)), null);

        for (int i = 0; i < 50; i++) {
            assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("approval-flow", "任意消息"),
                    "第 " + i + " 次：advisory 无状态、不积累反馈、不自修改路由规则（自修改属 R4，当前排除）");
        }
        // 同理：低置信 advisory 反复作用，规则路由结果恒定
        DefaultLayerRouter lowConf = new DefaultLayerRouter();
        lowConf.attachDecisionPlane(port(req -> choice("plan", 0.1)), null);
        for (int i = 0; i < 50; i++) {
            assertEquals(RuntimeLayer.REACT_LAYER, lowConf.route("default", "今天天气怎么样"));
        }
    }

    // ---------- 夹具 ----------

    private static StubDecisionPort port(Function<DecisionRequest, DecisionResponse> responder) {
        return new StubDecisionPort(responder);
    }

    private static DecisionResponse choice(String value, double confidence) {
        return DecisionResponse.of(Map.of("layer-route", DecisionAnswer.ofChoice(value, confidence, Map.of())));
    }

    /** 决策端口桩：按脚本函数应答并记录请求（domain 测试禁用 Mockito）。 */
    private static final class StubDecisionPort implements DecisionPort {
        final List<DecisionRequest> requests = new ArrayList<>();
        private final Function<DecisionRequest, DecisionResponse> responder;

        StubDecisionPort(Function<DecisionRequest, DecisionResponse> responder) {
            this.responder = responder;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            requests.add(request);
            return responder.apply(request);
        }
    }
}
