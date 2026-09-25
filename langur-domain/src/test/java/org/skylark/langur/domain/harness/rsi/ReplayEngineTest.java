package org.skylark.langur.domain.harness.rsi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R0 验收 - 回放验证引擎：原策略重放复现原结果（含分流路径一致）；劣化候选被拒绝；优化候选指标改善；
 * 只重放录制判定（引擎不持有 DecisionPort，结构性排除实时调用）；放松审批闸门（只收紧红线 P12②）被拒绝。
 * <p>纯 domain 单测，零外部依赖、无 Mockito（P1/测试约定）；全部离线确定性。</p>
 */
class ReplayEngineTest {

    private final ReplayEngine engine = new ReplayEngine();

    /** 三轮执行轨迹：r1(100tok/10ms) r2(200/20) r3(300/30)，成功。 */
    private static List<TrajectoryStep> threeSteps() {
        return List.of(
                TrajectoryStep.of(1, "reason", 100L, 10L),
                TrajectoryStep.of(2, "act", 200L, 20L),
                TrajectoryStep.of(3, "finalize", 300L, 30L));
    }

    private static DecisionAnswer choice(String value, double confidence) {
        return DecisionAnswer.ofChoice(value, confidence, Map.of(value, confidence));
    }

    /** 层路由判定：高置信 "run"，基线走 RUN，缺省阈值（routing 0.75）。 */
    private static Trajectory runTrajectory() {
        RecordedDecision d = new RecordedDecision("layer-route", 1,
                choice("run", 0.90d), ThresholdCategory.ROUTING, ReplayRoute.RUN, 5L);
        return Trajectory.of("task-1", true, DecisionThresholds.defaults(), threeSteps(), List.of(d));
    }

    @Test
    @DisplayName("原策略重放复现原结果：指标与分流路径一致，裁定 NEUTRAL")
    void shouldReproduceBaselineForOriginalStrategy() {
        Trajectory trajectory = runTrajectory();

        ReplayMetrics baseline = engine.replayBaseline(trajectory);
        assertTrue(baseline.success());
        assertEquals(3, baseline.rounds());
        assertEquals(600L, baseline.tokens());
        assertEquals(65L, baseline.latencyMillis()); // 10+20+30 执行 + 5 判定
        assertEquals(0, baseline.interceptions());
        assertEquals(List.of("run"), baseline.routePath());

        BaselineComparison comparison = engine.compare(trajectory, ReplayCandidate.baseline("replay-original"));
        assertEquals(BaselineComparison.Verdict.NEUTRAL, comparison.verdict());
        assertFalse(comparison.rejected());
        assertEquals(baseline, comparison.candidate(), "原策略重放须逐字段复现基线");
        assertEquals(0L, comparison.deltas().get("tokens"));
    }

    @Test
    @DisplayName("重放确定：同一轨迹两次基线重放逐字段相等")
    void shouldBeDeterministic() {
        Trajectory trajectory = runTrajectory();
        assertEquals(engine.replayBaseline(trajectory), engine.replayBaseline(trajectory));
    }

    @Test
    @DisplayName("优化候选（强制 SKIP 昂贵阶段）降本 → IMPROVED，不拒绝")
    void shouldAcceptCostReducingCandidate() {
        Trajectory trajectory = runTrajectory();

        ReplayCandidate candidate = ReplayCandidate.ofForcedRoutes("skip-stage",
                Map.of("layer-route", ReplayRoute.SKIP));
        BaselineComparison comparison = engine.compare(trajectory, candidate);

        assertEquals(BaselineComparison.Verdict.IMPROVED, comparison.verdict());
        assertFalse(comparison.rejected());
        assertEquals(2, comparison.candidate().rounds());
        assertEquals(500L, comparison.candidate().tokens()); // 避免 r1 的 100
        assertEquals(-100L, comparison.deltas().get("tokens"));
        assertEquals(List.of("skip"), comparison.candidate().routePath());
    }

    @Test
    @DisplayName("阈值调优重算录制判定分流：降 routing 阈值使 fail-closed→skip → IMPROVED")
    void shouldRecomputeRouteFromRecordedAnswerUnderCandidateThreshold() {
        // 录制判定：choice "skip" 置信 0.80；基线 routing 阈值 0.85 → 当时 fail-closed（保守，计 1 拦截）
        RecordedDecision d = new RecordedDecision("layer-route", 1,
                choice("skip", 0.80d), ThresholdCategory.ROUTING, ReplayRoute.FAIL_CLOSED, 5L);
        DecisionThresholds baselineThresholds = new DecisionThresholds(0.85d, 0.90d, 0.80d, 0.85d);
        Trajectory trajectory = Trajectory.of("task-2", true, baselineThresholds, threeSteps(), List.of(d));

        assertEquals(1, engine.replayBaseline(trajectory).interceptions());
        assertEquals(List.of("fail_closed"), engine.replayBaseline(trajectory).routePath());

        // 候选把 routing 阈值降到 0.75 → 重算 0.80≥0.75 且 choice=skip → SKIP，避免 r1 成本、消除拦截
        ReplayCandidate candidate = ReplayCandidate.ofThresholds("lower-routing",
                new DecisionThresholds(0.75d, 0.90d, 0.80d, 0.85d));
        BaselineComparison comparison = engine.compare(trajectory, candidate);

        assertEquals(BaselineComparison.Verdict.IMPROVED, comparison.verdict());
        assertFalse(comparison.rejected());
        assertEquals(List.of("skip"), comparison.candidate().routePath());
        assertEquals(0, comparison.candidate().interceptions());
        assertEquals(500L, comparison.candidate().tokens());
    }

    @Test
    @DisplayName("劣化候选（提前 TERMINATE 截断未完成）丢失成功 → DEGRADED，拒绝")
    void shouldRejectCandidateThatAbortsTask() {
        Trajectory trajectory = runTrajectory();

        ReplayCandidate candidate = ReplayCandidate.ofForcedRoutes("early-abort",
                Map.of("layer-route", ReplayRoute.TERMINATE));
        BaselineComparison comparison = engine.compare(trajectory, candidate);

        assertEquals(BaselineComparison.Verdict.DEGRADED, comparison.verdict());
        assertTrue(comparison.rejected());
        assertFalse(comparison.candidate().success());
        assertTrue(comparison.reason().contains("丢失成功"));
    }

    @Test
    @DisplayName("只收紧红线：候选放松审批闸门（APPROVE→RUN）→ DEGRADED，拒绝")
    void shouldRejectCandidateThatLoosensApprovalGate() {
        RecordedDecision approval = new RecordedDecision("approval-auto", 2,
                choice("approve", 0.95d), ThresholdCategory.APPROVAL_AUTO, ReplayRoute.APPROVE, 4L);
        Trajectory trajectory = Trajectory.of("task-3", true, DecisionThresholds.defaults(),
                threeSteps(), List.of(approval));

        assertEquals(1, engine.replayBaseline(trajectory).interceptions());

        ReplayCandidate candidate = ReplayCandidate.ofForcedRoutes("loosen-approval",
                Map.of("approval-auto", ReplayRoute.RUN));
        BaselineComparison comparison = engine.compare(trajectory, candidate);

        assertEquals(BaselineComparison.Verdict.DEGRADED, comparison.verdict());
        assertTrue(comparison.rejected());
        assertTrue(comparison.reason().contains("放松审批"));
    }

    @Test
    @DisplayName("生成级候选（PROMPT）离线重放录制响应 → 复现基线，NEUTRAL（无生成级反事实信号）")
    void shouldTreatGenerativeCandidateAsNeutralOffline() {
        Trajectory trajectory = runTrajectory();

        BaselineComparison comparison = engine.compare(trajectory,
                ReplayCandidate.ofGenerative("prompt-v2", CandidateKind.PROMPT));

        assertEquals(BaselineComparison.Verdict.NEUTRAL, comparison.verdict());
        assertFalse(comparison.rejected());
        assertEquals(engine.replayBaseline(trajectory), comparison.candidate());
    }

    @Test
    @DisplayName("轨迹不足：候选把基线 SKIP 的未录制轮次改回 RUN → 无法离线展开，NEUTRAL 并标注")
    void shouldMarkInsufficientTrajectoryWhenExpandingUnrecordedRound() {
        // r1 在基线被跳过（不在 steps 中）；steps 只含实际跑过的 r2/r3
        RecordedDecision skipped = new RecordedDecision("layer-route", 1,
                choice("skip", 0.90d), ThresholdCategory.ROUTING, ReplayRoute.SKIP, 5L);
        Trajectory trajectory = Trajectory.of("task-4", true, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(2, "act", 200L, 20L), TrajectoryStep.of(3, "finalize", 300L, 30L)),
                List.of(skipped));

        BaselineComparison comparison = engine.compare(trajectory,
                ReplayCandidate.ofForcedRoutes("force-run", Map.of("layer-route", ReplayRoute.RUN)));

        assertEquals(BaselineComparison.Verdict.NEUTRAL, comparison.verdict());
        assertFalse(comparison.rejected());
        assertTrue(comparison.reason().contains("无法展开"));
    }

    @Test
    @DisplayName("入参守卫：空轨迹/空候选/空 key/空 taskId 一律拒绝")
    void shouldRejectNullAndBlankInputs() {
        Trajectory trajectory = runTrajectory();
        assertThrows(IllegalArgumentException.class, () -> engine.replayBaseline(null));
        assertThrows(IllegalArgumentException.class, () -> engine.compare(null, ReplayCandidate.baseline("x")));
        assertThrows(IllegalArgumentException.class, () -> engine.compare(trajectory, null));
        assertThrows(IllegalArgumentException.class, () -> ReplayCandidate.baseline(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordedDecision(" ", 1, choice("run", 0.9d), ThresholdCategory.ROUTING,
                        ReplayRoute.RUN, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> Trajectory.of(" ", true, DecisionThresholds.defaults(), threeSteps(), List.of()));
    }
}
