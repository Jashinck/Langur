package org.skylark.langur.domain.harness.rsi;

import org.junit.jupiter.api.Test;

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
 * R-G 安全平面离线确定性测试（无 Mockito，匿名桩提案仓库）。
 * <p>覆盖：红线目标拒绝、深度超限拒绝、频率限流拒绝、happy path（提案→验证→应用）、高危强制人审、
 * 劣化验证拒绝、未验证不得应用、一键回滚、入参守卫。</p>
 */
class RsiSafetyPlaneTest {

    private static final class StubRepository implements RsiProposalRepository {
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

    private static BaselineComparison comparison(BaselineComparison.Verdict verdict, boolean rejected, String reason) {
        ReplayMetrics m = ReplayMetrics.of(true, 3, 600, 65, 0, List.of("run"));
        return new BaselineComparison("c", m, m, verdict, Map.of(), rejected, reason);
    }

    @Test
    void shouldRejectForbiddenTarget() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        RsiProposal proposal = RsiProposal.propose("p1", CandidateKind.PARAMS,
                "security.policy.decision-threshold", "x", 0, 1000L);

        SafetyGateResult result = plane.propose(proposal);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("forbidden target"), result.reason());
    }

    @Test
    void shouldRejectExcessiveDepth() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        RsiProposal proposal = RsiProposal.propose("p1", CandidateKind.PARAMS,
                "decision.threshold.routing", "0.9", 5, 1000L);

        SafetyGateResult result = plane.propose(proposal);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("depth"), result.reason());
    }

    @Test
    void shouldRejectFrequencyLimit() {
        StubRepository repo = new StubRepository();
        RsiSafetyPlane plane = new RsiSafetyPlane(repo, 3, 2, RsiSafetyPlane.DEFAULT_FORBIDDEN_TARGET_PREFIXES);
        assertTrue(plane.propose(RsiProposal.propose("p1", CandidateKind.PARAMS, "t", "1", 0, 1000L)).allowed());
        assertTrue(plane.propose(RsiProposal.propose("p2", CandidateKind.PARAMS, "t", "2", 0, 1001L)).allowed());

        SafetyGateResult third = plane.propose(RsiProposal.propose("p3", CandidateKind.PARAMS, "t", "3", 0, 1002L));

        assertFalse(third.allowed());
        assertTrue(third.reason().contains("frequency"), third.reason());
    }

    @Test
    void shouldProposeValidateAndApplyNonHighRisk() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        assertTrue(plane.propose(RsiProposal.propose("p1", CandidateKind.PROMPT, "prompt.template", "t", 0, 1000L)).allowed());
        assertTrue(plane.validate("p1", comparison(BaselineComparison.Verdict.IMPROVED, false, "improved")).allowed());
        // 非策略级（PROMPT）无需人审
        assertTrue(plane.apply("p1", false).allowed());
    }

    @Test
    void shouldRequireHumanApprovalForPolicyLevelProposal() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        plane.propose(RsiProposal.propose("p1", CandidateKind.THRESHOLD, "decision.threshold.routing", "0.8", 0, 1000L));
        plane.validate("p1", comparison(BaselineComparison.Verdict.IMPROVED, false, "improved"));

        assertFalse(plane.apply("p1", false).allowed(), "策略级提案未经人审不得应用");
        assertTrue(plane.apply("p1", true).allowed());
    }

    @Test
    void shouldRejectDegradedAtValidation() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        plane.propose(RsiProposal.propose("p1", CandidateKind.THRESHOLD, "decision.threshold.approval-auto", "0.5", 0, 1000L));

        SafetyGateResult result = plane.validate("p1",
                comparison(BaselineComparison.Verdict.DEGRADED, true, "放松审批闸门"));

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("degraded"), result.reason());
        // 校验拒绝后不得应用
        assertFalse(plane.apply("p1", true).allowed());
    }

    @Test
    void shouldNotApplyWithoutValidation() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        plane.propose(RsiProposal.propose("p1", CandidateKind.PROMPT, "prompt.template", "t", 0, 1000L));

        SafetyGateResult result = plane.apply("p1", false);

        assertFalse(result.allowed(), "未经验证的提案无法生效");
        assertTrue(result.reason().contains("not validated"), result.reason());
    }

    @Test
    void shouldRollbackAppliedProposal() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        plane.propose(RsiProposal.propose("p1", CandidateKind.PROMPT, "prompt.template", "t", 0, 1000L));
        plane.validate("p1", comparison(BaselineComparison.Verdict.IMPROVED, false, "improved"));
        plane.apply("p1", false);

        assertTrue(plane.rollback("p1"));
        assertFalse(plane.rollback("p1"), "已回滚不可重复回滚");
    }

    @Test
    void shouldRejectNullProposalAndUnknownIds() {
        RsiSafetyPlane plane = new RsiSafetyPlane(new StubRepository());
        assertFalse(plane.propose(null).allowed());
        assertFalse(plane.validate("absent", comparison(BaselineComparison.Verdict.IMPROVED, false, "x")).allowed());
        assertFalse(plane.apply("absent", false).allowed());
    }

    @Test
    void shouldRejectNullRepository() {
        assertThrows(IllegalArgumentException.class, () -> new RsiSafetyPlane(null));
    }

    @Test
    void shouldPreserveChecksumAcrossStatusTransitions() {
        StubRepository repo = new StubRepository();
        RsiSafetyPlane plane = new RsiSafetyPlane(repo);
        RsiProposal proposal = RsiProposal.propose("p1", CandidateKind.PROMPT, "prompt.template", "t", 0, 1000L);
        plane.propose(proposal);

        assertEquals(proposal.checksum(), repo.findById("p1").orElseThrow().checksum(), "审计校验和应跨状态迁移不变");
    }
}
