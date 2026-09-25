package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionType;

import java.util.Locale;

/**
 * 回放分流动作（R0）。语义对齐 infra {@code ThresholdRouter.RouteAction}，但落 domain（P2 单向依赖：
 * domain 不得 import infra），故在域内以纯 JDK 重实现同一映射，供 {@link ReplayEngine} 离线重算路由。
 * <p>回放<b>只重算录制的 Jev 判定</b>（{@link DecisionAnswer} 来自轨迹录制，绝不重新联网，C2/DD12）：
 * 置信达阈值 → 按 CHOICE 值程序化分流；低置信/缺失 → {@link #FAIL_CLOSED}（与运行期 fail-closed 一致，P12③）。</p>
 */
public enum ReplayRoute {

    /** 继续/执行下一阶段。 */
    RUN,
    /** 跳过（不必要的昂贵阶段）——回放中避免该轮成本。 */
    SKIP,
    /** 分支跳转。 */
    BRANCH,
    /** 进入审批（保守路径，计入拦截）。 */
    APPROVE,
    /** 中断/终止——回放中截断后续轮次。 */
    TERMINATE,
    /** 低置信/缺失 → fail-closed（保守路径，计入拦截）。 */
    FAIL_CLOSED;

    /** 是否为"保守路径"（人审/规则兜底），回放中计入拦截次数。 */
    public boolean isConservative() {
        return this == APPROVE || this == FAIL_CLOSED;
    }

    /**
     * 由录制判定 + 置信阈值重算分流动作（镜像 {@code ThresholdRouter.route}，域内纯实现）。
     *
     * @param answer    录制的判定答案（可空 → fail-closed）
     * @param threshold 该判定类别的置信阈值
     * @return 分流动作
     */
    public static ReplayRoute fromAnswer(DecisionAnswer answer, double threshold) {
        if (answer == null || answer.confidence() < threshold) {
            return FAIL_CLOSED;
        }
        if (answer.type() != DecisionType.CHOICE || answer.choice() == null) {
            return RUN;
        }
        return switch (answer.choice().toLowerCase(Locale.ROOT)) {
            case "skip", "skip-stage", "run-skip" -> SKIP;
            case "branch", "branch-to-stage" -> BRANCH;
            case "approve", "auto-approve", "require-approval" -> APPROVE;
            case "abort", "terminate" -> TERMINATE;
            default -> RUN;
        };
    }
}
