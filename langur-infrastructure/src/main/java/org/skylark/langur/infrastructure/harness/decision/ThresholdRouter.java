package org.skylark.langur.infrastructure.harness.decision;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.Locale;

/**
 * 决策平面阈值路由装饰器（J3）。位于装饰链中包裹后端（{@code Caching ⊃ Threshold ⊃ backend}）：
 * <ul>
 *   <li>{@link #decide} 透传后端判定（保留 confidence 供消费方分流）；</li>
 *   <li>{@link #route} 供各插入点（J4–J10）调用：{@code confidence ≥ 阈值} → 按 CHOICE 值程序化分流
 *       （run/skip/branch/approve/terminate）；{@code < 阈值} 或答案缺失 → <b>fail-closed</b>
 *       （{@link RouteAction#FAIL_CLOSED}，由消费方走升级/人审/回退规则，P10/P12③）；</li>
 *   <li>每次分流发 {@code decision_route_counts} 指标（经 H5 {@link EvaluationService}，按 action 计数）。</li>
 * </ul>
 * <p>对安全/审批闸门，消费方须保证只收紧不放松（P12②，如 J5 CRITICAL 恒人审）。本类不带 {@code @Component}，
 * 由 J3 {@code DecisionConfiguration} 装配并暴露为 Bean（供消费方注入 {@link #route}）。</p>
 */
@Slf4j
public class ThresholdRouter implements DecisionPort {

    /** 程序化分流动作（对齐 J4 阶段闸门 run-next/skip/branch-to-stage/abort/require-approval）。 */
    public enum RouteAction {
        /** 继续/执行下一阶段。 */
        RUN,
        /** 跳过（不必要的昂贵阶段）。 */
        SKIP,
        /** 分支跳转到指定阶段。 */
        BRANCH,
        /** 进入审批（自动放行或人审由消费方按红线裁定）。 */
        APPROVE,
        /** 中断/终止。 */
        TERMINATE,
        /** 低置信/缺失 → fail-closed，消费方走最保守分支（升级/人审/回退规则）。 */
        FAIL_CLOSED
    }

    private final DecisionPort delegate;
    private final DecisionThresholds thresholds;
    private final EvaluationService evaluationService;

    public ThresholdRouter(DecisionPort delegate,
                           DecisionThresholds thresholds,
                           EvaluationService evaluationService) {
        this.delegate = delegate;
        this.thresholds = (thresholds == null) ? DecisionThresholds.defaults() : thresholds;
        this.evaluationService = evaluationService;
    }

    /** 被包裹的下层后端（供装配顺序校验）。 */
    public DecisionPort getDelegate() {
        return delegate;
    }

    /** 各插入点置信阈值（消费方据插入点选取 routing/approvalAuto/artifactAccept/completion）。 */
    public DecisionThresholds getThresholds() {
        return thresholds;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        return delegate.decide(request);
    }

    /**
     * 按置信阈值把判定分流为程序化动作；低置信/缺失一律 fail-closed（P12③）。发 {@code decision_route_counts}。
     *
     * @param answer    判定答案（可空 → fail-closed）
     * @param threshold 该插入点置信阈值
     * @return 分流动作；{@link RouteAction#FAIL_CLOSED} 表示须走最保守分支
     */
    public RouteAction route(DecisionAnswer answer, double threshold) {
        RouteAction action = (answer == null || answer.confidence() < threshold)
                ? RouteAction.FAIL_CLOSED
                : mapChoice(answer);
        emitRouteCount(action);
        return action;
    }

    /** 高置信 CHOICE 值 → 动作映射；非 CHOICE（PROBABILITY/SCORE）高置信默认 RUN（由消费方按 value 再判）。 */
    private RouteAction mapChoice(DecisionAnswer answer) {
        if (answer.type() != DecisionType.CHOICE || answer.choice() == null) {
            return RouteAction.RUN;
        }
        return switch (answer.choice().toLowerCase(Locale.ROOT)) {
            case "skip", "skip-stage", "run-skip" -> RouteAction.SKIP;
            case "branch", "branch-to-stage" -> RouteAction.BRANCH;
            case "approve", "auto-approve", "require-approval" -> RouteAction.APPROVE;
            case "abort", "terminate" -> RouteAction.TERMINATE;
            default -> RouteAction.RUN;
        };
    }

    private void emitRouteCount(RouteAction action) {
        if (evaluationService == null) {
            return;
        }
        try {
            ExecutionMetrics metrics = ExecutionMetrics.of("decision-plane");
            metrics.record(MetricDimension.DECISION, "decision_route_counts",
                    action.name().toLowerCase(Locale.ROOT));
            evaluationService.report(metrics);
        } catch (RuntimeException e) {
            log.debug("[DECISION] route count report failed, silently dropped", e);
        }
    }
}
