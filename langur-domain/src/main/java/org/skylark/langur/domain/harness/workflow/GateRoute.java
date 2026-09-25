package org.skylark.langur.domain.harness.workflow;

import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionType;

import java.util.Locale;

/**
 * J4 - Workflow 阶段决策闸门的分流结果（插入点 ①，v3.0 §3.1）。
 * <p>与 infra {@code ThresholdRouter.RouteAction} 语义对齐，但落在 domain（P1/P2：domain 不依赖 infra）：
 * {@code confidence ≥ 阈值} → 按 CHOICE 值程序化分流；低置信/答案缺失 → {@link #FAIL_CLOSED}，
 * 消费方（{@code WorkflowExecutionLoop}）走<b>默认固定顺序</b>兜底（P10/P12③）。</p>
 */
public enum GateRoute {

    /** 继续执行下一阶段（默认顺序）。 */
    RUN_NEXT,
    /** 跳过下一阶段（不必要的昂贵阶段）。 */
    SKIP,
    /** 分支跳转到闸门声明的目标阶段。 */
    BRANCH,
    /** 中断工作流（保守终止）。 */
    ABORT,
    /** 升级人审：下一阶段执行前强制经审批闸门（只收紧，P12②）。 */
    REQUIRE_APPROVAL,
    /** 低置信/缺失 → fail-closed，走默认顺序兜底。 */
    FAIL_CLOSED;

    /**
     * 按置信阈值把判定答案分流为闸门动作（对齐 J4 配置的 run-next/skip/branch-to-stage/abort/require-approval）。
     *
     * @param answer    判定答案（可空 → fail-closed）
     * @param threshold 闸门置信阈值
     * @return 分流动作
     */
    public static GateRoute of(DecisionAnswer answer, double threshold) {
        if (answer == null || answer.confidence() < threshold) {
            return FAIL_CLOSED;
        }
        if (answer.type() != DecisionType.CHOICE || answer.choice() == null) {
            return RUN_NEXT;
        }
        return switch (answer.choice().toLowerCase(Locale.ROOT)) {
            case "skip", "skip-stage", "run-skip" -> SKIP;
            case "branch", "branch-to-stage" -> BRANCH;
            case "abort", "terminate" -> ABORT;
            case "approve", "auto-approve", "require-approval" -> REQUIRE_APPROVAL;
            default -> RUN_NEXT;
        };
    }
}
