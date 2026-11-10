package org.skylark.langur.domain.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4 策略自优化器离线确定性测试（无 Mockito，匿名桩提案仓库；回放引擎为 R0 真实实现）。
 * <p>覆盖：回放择优选 IMPROVED 最低成本、淘汰劣化候选、无改进返回 empty、阈值调优产出 THRESHOLD 提案、
 * 劣化自动回滚、入参守卫。</p>
 */
class StrategyOptimizerTest {

    private final StrategyOptimizer optimizer = new StrategyOptimizer(new ReplayEngine());

    private static final class StubRepo implements RsiProposalRepository {
        final ConcurrentMap<String, RsiProposal> map = new ConcurrentHashMap<>();

        @Override
        public void save(RsiProposal proposal) {
            map.put(proposal.id(), proposal);
        }

        @Override
        public Optional<RsiProposal> findById(String id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public List<RsiProposal> listAll() {
            return List.copyOf(map.values());
        }
    }

    /** 三步骤轨迹 + 两个 RUN 判定：SKIP 逐轮减本（IMPROVED）、TERMINATE 丢失成功（DEGRADED）。 */
    private static Trajectory trajectory() {
        return Trajectory.of("t", true, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "a", 100, 10),
                        TrajectoryStep.of(2, "b", 200, 20),
                        TrajectoryStep.of(3, "c", 300, 30)),
                List.of(new RecordedDecision("d1", 1, null, ThresholdCategory.ROUTING, ReplayRoute.RUN, 5L),
                        new RecordedDecision("d2", 2, null, ThresholdCategory.ROUTING, ReplayRoute.RUN, 5L)));
    }

    private static ReplayCandidate forced(String id, Map<String, ReplayRoute> routes) {
        return ReplayCandidate.ofForcedRoutes(id, routes);
    }

    @Test
    void shouldSelectBestImprovedCandidateByLowestTokens() {
        Optional<ReplayCandidate> best = optimizer.selectBest(trajectory(), List.of(
                forced("skip1", Map.of("d1", ReplayRoute.SKIP)),                    // tokens 500
                forced("skip2", Map.of("d1", ReplayRoute.SKIP, "d2", ReplayRoute.SKIP)), // tokens 300
                forced("terminate", Map.of("d1", ReplayRoute.TERMINATE))));          // DEGRADED

        assertTrue(best.isPresent());
        assertEquals("skip2", best.get().candidateId(), "应选 IMPROVED 中 Token 最低者");
    }

    @Test
    void shouldSkipDegradedCandidatesAndReturnEmptyWhenNoneImprove() {
        Optional<ReplayCandidate> best = optimizer.selectBest(trajectory(), List.of(
                forced("terminate", Map.of("d1", ReplayRoute.TERMINATE))));

        assertTrue(best.isEmpty(), "全部劣化 → 无改进，宁缺毋滥（P11）");
    }

    @Test
    void shouldReturnEmptyForNullOrEmptyInputs() {
        assertTrue(optimizer.selectBest(null, List.of(forced("x", Map.of()))).isEmpty());
        assertTrue(optimizer.selectBest(trajectory(), List.of()).isEmpty());
        assertTrue(optimizer.selectBest(trajectory(), null).isEmpty());
    }

    @Test
    void shouldTuneThresholdsIntoProposalWhenImproved() {
        // 判定 answer=choice("skip") 置信 0.70：基线 routing 0.75 → FAIL_CLOSED；候选 0.60 → SKIP → IMPROVED
        Trajectory t = Trajectory.of("t", true, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "a", 100, 10)),
                List.of(new RecordedDecision("d1", 1, DecisionAnswer.ofChoice("skip", 0.70, Map.of()),
                        ThresholdCategory.ROUTING, ReplayRoute.FAIL_CLOSED, 5L)));

        Optional<RsiProposal> proposal = optimizer.tuneThresholds("p1", t, List.of(
                new DecisionThresholds(0.60, 0.90, 0.80, 0.85),
                new DecisionThresholds(0.80, 0.90, 0.80, 0.85)));

        assertTrue(proposal.isPresent());
        assertEquals(CandidateKind.THRESHOLD, proposal.get().kind());
        assertEquals("decision.threshold", proposal.get().target());
        assertTrue(proposal.get().payload().contains("routing=0.6"), proposal.get().payload());
    }

    @Test
    void shouldReturnEmptyTuningWhenNoCandidateImproves() {
        Trajectory t = trajectory();
        Optional<RsiProposal> proposal = optimizer.tuneThresholds("p1", t, List.of(
                new DecisionThresholds(0.90, 0.90, 0.80, 0.85)));

        assertTrue(proposal.isEmpty(), "无改进阈值 → 不做负优化");
    }

    @Test
    void shouldAutoRollbackWhenNewTrajectoryDegraded() {
        StubRepo repo = new StubRepo();
        RsiSafetyPlane plane = new RsiSafetyPlane(repo);
        plane.propose(RsiProposal.propose("p1", CandidateKind.PROMPT, "prompt.template", "x", 0, 1000L));
        plane.validate("p1", new BaselineComparison("p1",
                ReplayMetrics.of(true, 3, 600, 65, 0, List.of("run")),
                ReplayMetrics.of(true, 3, 600, 65, 0, List.of("run")),
                BaselineComparison.Verdict.IMPROVED, Map.of(), false, "ok"));
        plane.apply("p1", false);

        boolean rolled = optimizer.rollbackIfDegraded("p1", plane, trajectory(),
                forced("terminate", Map.of("d1", ReplayRoute.TERMINATE)));

        assertTrue(rolled, "新轨迹劣化 → 自动回滚");
        assertEquals(RsiProposalStatus.ROLLED_BACK, repo.findById("p1").orElseThrow().status());
    }

    @Test
    void shouldNotRollbackWhenStillImproved() {
        StubRepo repo = new StubRepo();
        RsiSafetyPlane plane = new RsiSafetyPlane(repo);
        plane.propose(RsiProposal.propose("p1", CandidateKind.PROMPT, "prompt.template", "x", 0, 1000L));
        plane.validate("p1", new BaselineComparison("p1",
                ReplayMetrics.of(true, 3, 600, 65, 0, List.of("run")),
                ReplayMetrics.of(true, 3, 600, 65, 0, List.of("run")),
                BaselineComparison.Verdict.IMPROVED, Map.of(), false, "ok"));
        plane.apply("p1", false);

        boolean rolled = optimizer.rollbackIfDegraded("p1", plane, trajectory(),
                forced("skip1", Map.of("d1", ReplayRoute.SKIP)));

        assertFalse(rolled, "仍改进 → 不回滚");
    }

    @Test
    void shouldRejectNullReplayEngine() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyOptimizer(null));
    }
}
