package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 回放验证引擎（R0，RSI 安全前提）。在历史轨迹上做<b>确定性反事实重放</b>：加载录制的执行步骤与 Jev 判定 →
 * 注入候选策略（阈值/强制路由）→ 离线重算分流（<b>只重放录制判定，绝不重新联网</b>，C2/DD12）→ 采集 V 指标 →
 * 与基线对比裁定。
 * <p>纯 domain 服务，无副作用、零外部依赖（P1）；不持有任何 {@code DecisionPort}——回放路径结构性地不可能触发
 * 实时判定调用。裁定遵循 RSI 红线（P11/P12）：</b>劣化即拒绝</b>、<b>放松审批闸门即拒绝</b>（只收紧不放松），
 * 且回放通过<b>不等于生效</b>——候选仅是提案，生效须经 R-G 灰度 + 高危人审。</p>
 *
 * <p><b>成本模型（确定性、诚实）</b>：轨迹 {@link Trajectory#steps()} 是原执行<b>实际跑过</b>的轮次。候选相对基线
 * 只能<b>移除</b>成本（SKIP 避免该轮、TERMINATE 截断其后轮次），不会凭空展开未录制的轮次（RUN 覆盖基线 SKIP 时
 * 标记"轨迹不足"，如实反映离线无生成级反事实信号）。因此生成级候选（PROMPT/SKILL/PARAMS）重放录制响应 → 复现基线
 * → {@link BaselineComparison.Verdict#NEUTRAL}。</p>
 */
public class ReplayEngine {

    /**
     * 原策略重放：以轨迹基线阈值 + 录制的 {@code baselineRoute} 复现原始指标与分流路径（确定性）。
     *
     * @param trajectory 历史轨迹
     * @return 基线指标剖面
     */
    public ReplayMetrics replayBaseline(Trajectory trajectory) {
        requireTrajectory(trajectory);
        long tokens = 0L;
        long latency = 0L;
        for (TrajectoryStep step : trajectory.steps()) {
            tokens += step.tokens();
            latency += step.latencyMillis();
        }
        int interceptions = 0;
        List<String> routePath = new ArrayList<>();
        for (RecordedDecision decision : trajectory.decisions()) {
            latency += decision.latencyMillis();
            if (decision.baselineRoute().isConservative()) {
                interceptions++;
            }
            routePath.add(routeName(decision.baselineRoute()));
        }
        return ReplayMetrics.of(trajectory.success(), trajectory.steps().size(), tokens,
                latency, interceptions, routePath);
    }

    /**
     * 反事实重放并与基线对比。
     *
     * @param trajectory 历史轨迹（基线）
     * @param candidate  候选策略
     * @return 基线对比报告（含裁定与是否拒绝）
     */
    public BaselineComparison compare(Trajectory trajectory, ReplayCandidate candidate) {
        requireTrajectory(trajectory);
        if (candidate == null) {
            throw new IllegalArgumentException("ReplayCandidate must not be null");
        }
        ReplayMetrics baseline = replayBaseline(trajectory);
        Candidate candidateMetrics = replayCandidate(trajectory, candidate);

        Map<String, Long> deltas = new LinkedHashMap<>();
        deltas.put("rounds", (long) candidateMetrics.metrics.rounds() - baseline.rounds());
        deltas.put("tokens", candidateMetrics.metrics.tokens() - baseline.tokens());
        deltas.put("latencyMillis", candidateMetrics.metrics.latencyMillis() - baseline.latencyMillis());
        deltas.put("interceptions", (long) candidateMetrics.metrics.interceptions() - baseline.interceptions());

        return verdict(candidate.candidateId(), baseline, candidateMetrics, deltas);
    }

    /** 候选重放：按候选阈值/强制路由重算每个录制判定的分流，并施加相对基线的成本/安全效应。 */
    private Candidate replayCandidate(Trajectory trajectory, ReplayCandidate candidate) {
        DecisionThresholds thresholds = (candidate.thresholds() == null)
                ? trajectory.baselineThresholds()
                : candidate.thresholds();

        long tokens = 0L;
        long latency = 0L;
        for (TrajectoryStep step : trajectory.steps()) {
            tokens += step.tokens();
            latency += step.latencyMillis();
        }
        for (RecordedDecision decision : trajectory.decisions()) {
            latency += decision.latencyMillis();
        }
        int rounds = trajectory.steps().size();
        boolean success = trajectory.success();

        Set<Integer> removedRounds = new HashSet<>();
        boolean safetyLoosened = false;
        boolean insufficientTrajectory = false;
        int interceptions = 0;
        List<String> routePath = new ArrayList<>();

        for (RecordedDecision decision : trajectory.decisions()) {
            ReplayRoute route = candidate.forcedRoutes().containsKey(decision.key())
                    ? candidate.forcedRoutes().get(decision.key())
                    : ReplayRoute.fromAnswer(decision.answer(), decision.category().thresholdOf(thresholds));
            routePath.add(routeName(route));
            if (route.isConservative()) {
                interceptions++;
            }

            if (route == decision.baselineRoute()) {
                continue;
            }
            // 只收紧红线（P12②）：审批类别从保守路径放松为非保守 → 安全劣化。
            if (decision.category() == ThresholdCategory.APPROVAL_AUTO
                    && decision.baselineRoute().isConservative()
                    && !route.isConservative()) {
                safetyLoosened = true;
            }
            switch (route) {
                case SKIP -> {
                    if (removedRounds.add(decision.round())) {
                        var step = trajectory.stepAtRound(decision.round());
                        if (step.isPresent()) {
                            tokens -= step.get().tokens();
                            latency -= step.get().latencyMillis();
                            rounds--;
                        }
                    }
                }
                case TERMINATE -> {
                    int lastRound = trajectory.lastRound();
                    for (TrajectoryStep step : trajectory.steps()) {
                        if (step.round() > decision.round() && step.round() <= lastRound
                                && removedRounds.add(step.round())) {
                            tokens -= step.tokens();
                            latency -= step.latencyMillis();
                            rounds--;
                            success = false; // 截断未完成 → 丢失成功
                        }
                    }
                }
                case RUN -> {
                    if (decision.baselineRoute() == ReplayRoute.SKIP) {
                        // 基线跳过、未录制该轮成本 → 离线无法展开，如实标记轨迹不足。
                        insufficientTrajectory = true;
                    }
                }
                default -> {
                    // BRANCH/APPROVE/FAIL_CLOSED 的路由变化只影响拦截计数（已在上方重算）。
                }
            }
        }

        ReplayMetrics metrics = ReplayMetrics.of(success, rounds, tokens, latency, interceptions, routePath);
        return new Candidate(metrics, safetyLoosened, insufficientTrajectory);
    }

    /** 裁定：安全放松 / 丢失成功 / 抬高成本 → DEGRADED 拒绝；降本无回归 → IMPROVED；否则 NEUTRAL。 */
    private BaselineComparison verdict(String candidateId,
                                       ReplayMetrics baseline,
                                       Candidate candidate,
                                       Map<String, Long> deltas) {
        ReplayMetrics c = candidate.metrics;
        if (candidate.safetyLoosened) {
            return new BaselineComparison(candidateId, baseline, c, BaselineComparison.Verdict.DEGRADED,
                    deltas, true, "候选放松审批闸门，违反只收紧红线（P12②）");
        }
        if (baseline.success() && !c.success()) {
            return new BaselineComparison(candidateId, baseline, c, BaselineComparison.Verdict.DEGRADED,
                    deltas, true, "候选提前中断任务，丢失成功");
        }
        if (c.tokens() > baseline.tokens() || c.latencyMillis() > baseline.latencyMillis()
                || c.rounds() > baseline.rounds()) {
            return new BaselineComparison(candidateId, baseline, c, BaselineComparison.Verdict.DEGRADED,
                    deltas, true, "候选抬高成本（轮次/Token/延迟）");
        }
        if (c.tokens() < baseline.tokens() || c.latencyMillis() < baseline.latencyMillis()
                || c.rounds() < baseline.rounds()) {
            return new BaselineComparison(candidateId, baseline, c, BaselineComparison.Verdict.IMPROVED,
                    deltas, false, "候选降低成本且无成功/安全回归");
        }
        String reason = candidate.insufficientTrajectory
                ? "离线无法展开未录制轮次，无生成级反事实信号"
                : "录制重放下与基线无可测差异";
        return new BaselineComparison(candidateId, baseline, c, BaselineComparison.Verdict.NEUTRAL,
                deltas, false, reason);
    }

    private static String routeName(ReplayRoute route) {
        return route.name().toLowerCase(Locale.ROOT);
    }

    private static void requireTrajectory(Trajectory trajectory) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory must not be null");
        }
    }

    /** 候选重放的内部结果载体（指标 + 红线标记）。 */
    private record Candidate(ReplayMetrics metrics, boolean safetyLoosened, boolean insufficientTrajectory) {
    }
}
