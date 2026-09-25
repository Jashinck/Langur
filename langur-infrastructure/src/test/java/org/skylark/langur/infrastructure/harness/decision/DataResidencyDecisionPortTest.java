package org.skylark.langur.infrastructure.harness.decision;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J3 - {@link DataResidencyDecisionPort} 数据驻留路由测试（DD10/P12 红线⑤）。
 * <p>验收点：敏感状态强制走 local（桩断言绝不发往第三方 primary）；敏感且无 local 端点 → fail-closed
 * 规则兜底；未命中敏感 → 走 primary；未配置敏感命名空间 → 永不判敏感。</p>
 */
class DataResidencyDecisionPortTest {

    private static final List<String> SENSITIVE = List.of("legal-contracts", "code");

    @Test
    void shouldForceLocalBackendForSensitiveState() {
        RecordingPort primary = new RecordingPort("primary");
        RecordingPort local = new RecordingPort("local");
        RecordingPort fallback = new RecordingPort("fallback");
        DataResidencyDecisionPort port =
                new DataResidencyDecisionPort(primary, local, fallback, SENSITIVE);

        DecisionResponse response = port.decide(request("[legal-contracts] 合同违约条款草稿"));

        assertSame(local.response, response);
        assertEquals(1, local.calls);
        assertEquals(0, primary.calls, "敏感状态绝不发往第三方 primary");
        assertEquals(0, fallback.calls);
    }

    @Test
    void shouldMatchSensitiveNamespaceCaseInsensitively() {
        RecordingPort primary = new RecordingPort("primary");
        RecordingPort local = new RecordingPort("local");
        DataResidencyDecisionPort port =
                new DataResidencyDecisionPort(primary, local, new RecordingPort("fallback"), SENSITIVE);

        port.decide(request("LEGAL-CONTRACTS: breach clause"));

        assertEquals(1, local.calls);
        assertEquals(0, primary.calls);
    }

    @Test
    void shouldRouteNonSensitiveStateToPrimary() {
        RecordingPort primary = new RecordingPort("primary");
        RecordingPort local = new RecordingPort("local");
        DataResidencyDecisionPort port =
                new DataResidencyDecisionPort(primary, local, new RecordingPort("fallback"), SENSITIVE);

        DecisionResponse response = port.decide(request("今天天气不错，安排一次站会"));

        assertSame(primary.response, response);
        assertEquals(1, primary.calls);
        assertEquals(0, local.calls);
    }

    @Test
    void shouldFailClosedToRuleFallbackWhenSensitiveWithoutLocal() {
        RecordingPort primary = new RecordingPort("primary");
        RecordingPort fallback = new RecordingPort("fallback");
        DataResidencyDecisionPort port =
                new DataResidencyDecisionPort(primary, null, fallback, SENSITIVE);

        DecisionResponse response = port.decide(request("code 仓库密钥扫描结果"));

        assertSame(fallback.response, response);
        assertEquals(1, fallback.calls);
        assertEquals(0, primary.calls, "无 local 端点时敏感状态也绝不发往第三方");
    }

    @Test
    void shouldNeverTreatAsSensitiveWhenNamespacesEmpty() {
        RecordingPort primary = new RecordingPort("primary");
        DataResidencyDecisionPort port =
                new DataResidencyDecisionPort(primary, new RecordingPort("local"),
                        new RecordingPort("fallback"), List.of());

        port.decide(request("[legal-contracts] 合同违约条款草稿"));

        assertEquals(1, primary.calls);
        assertFalse(port.isSensitive("legal-contracts anything"));
        assertFalse(port.isSensitive(null));
        assertFalse(port.isSensitive("  "));
        assertTrue(new DataResidencyDecisionPort(primary, null, null, SENSITIVE)
                .isSensitive("see code review notes"));
        assertFalse(new DataResidencyDecisionPort(primary, null, null, null)
                .isSensitive("legal-contracts"));
    }

    private static DecisionRequest request(String state) {
        return DecisionRequest.of(state, DecisionQuestion.probability("risk", "any risk?"));
    }

    /** 记录调用次数与固定响应的桩端口。 */
    private static final class RecordingPort implements DecisionPort {
        final String name;
        int calls;
        final DecisionResponse response = DecisionResponse.of(Map.of());

        RecordingPort(String name) {
            this.name = name;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls++;
            return response;
        }
    }
}
