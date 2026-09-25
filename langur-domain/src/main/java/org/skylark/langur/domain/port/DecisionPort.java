package org.skylark.langur.domain.port;

import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;

/**
 * 决策平面判定端口（J1，System-1）。与 {@link LLMPort} / RerankPort / ApprovalPort 同构，
 * 经 {@code ObjectProvider} 松耦合注入到 E/T/C/L 各消费点，缺失即降级（P10）。
 * <p>后端无关（P3 依赖倒置 / P5 开闭）：托管 Jev、自部署 Kev/Laya、规则兜底均为其不同实现。
 * 决策平面是横切<b>端口</b>而非第七组件（P4 不破）；对安全/审批闸门恒为 advisory，只收紧不放松（P12）。</p>
 * <p>实现方须对每次判定可录制（{@code record=true} 常开，R0 回放前向兼容，C2/DD12）。</p>
 */
public interface DecisionPort {

    /**
     * 对一次请求（可含多问题，批量/投机扇出）求值。
     *
     * @param request 判定请求（{@code state} + 可选 {@code model} + 一批 {@code questions}）
     * @return 按问题 {@code key} 映射的类型化判定 + 置信度 + Token 计量；不返回 {@code null}
     */
    DecisionResponse decide(DecisionRequest request);
}
