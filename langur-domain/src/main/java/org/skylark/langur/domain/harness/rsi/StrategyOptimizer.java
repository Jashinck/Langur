package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 策略自优化器（R4，RSI L4 → 决策平面达 D3）——从候选策略集中经 R0 回放<b>离线择优</b>，产出 R-G 提案。
 * <p>① <b>回放择优（贪婪 bandit）</b>：候选集逐一离线重放（绝不联网），淘汰劣化/放松安全/被拒候选，
 * 选 IMPROVED 中成本（Token）最低者；无改进返回 empty（宁缺毋滥，P11）。② <b>阈值调优（C1）</b>：从候选
 * 阈值组选出回放最优者，产出 {@code THRESHOLD} 提案（决策平面阈值自优化 D3，经 J8 预留接缝而非改路由源码）；
 * ③ <b>劣化自动回滚</b>：应用后的提案对新轨迹重放，劣化即经 {@link RsiSafetyPlane} 一键回滚。
 * 纯领域实现（无 Spring 依赖），由 start 层装配。M5 生成四类提案（Prompt/路由规则/超参/DecisionEngineSPI 策略）
 * 与 canary 灰度前端为后续编排（R4 交付离线择优 + 提案 + 自动回滚底座）。</p>
 */
public class StrategyOptimizer {

    private final ReplayEngine replayEngine;

    public StrategyOptimizer(ReplayEngine replayEngine) {
        if (replayEngine == null) {
            throw new IllegalArgumentException("ReplayEngine must not be null");
        }
        this.replayEngine = replayEngine;
    }

    /**
     * 回放择优（贪婪 bandit）：逐一重放候选，淘汰 rejected / DEGRADED（劣化或放松安全），
     * 在 IMPROVED 中选 Token 最低者。无改进返回 {@link Optional#empty()}。
     */
    public Optional<ReplayCandidate> selectBest(Trajectory trajectory, List<ReplayCandidate> candidates) {
        if (trajectory == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        ReplayCandidate best = null;
        long bestTokens = Long.MAX_VALUE;
        for (ReplayCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            BaselineComparison comparison = replayEngine.compare(trajectory, candidate);
            // bandit 只保留"优于基线"的臂：劣化/放松安全/无差异（NEUTRAL）均不选（宁缺毋滥，P11）
            if (comparison.rejected() || comparison.verdict() != BaselineComparison.Verdict.IMPROVED) {
                continue;
            }
            if (comparison.candidate().tokens() < bestTokens) {
                best = candidate;
                bestTokens = comparison.candidate().tokens();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 阈值调优（C1）：从候选阈值组离线选出回放最优者，产出 {@code THRESHOLD} 提案（默认候选、不生效，
     * 须经 R-G 三段式）。无改进返回 empty（不做负优化）。
     */
    public Optional<RsiProposal> tuneThresholds(String proposalId, Trajectory trajectory,
                                                List<DecisionThresholds> candidateThresholds) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId must not be blank");
        }
        if (candidateThresholds == null || candidateThresholds.isEmpty()) {
            return Optional.empty();
        }
        List<ReplayCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < candidateThresholds.size(); i++) {
            DecisionThresholds thresholds = candidateThresholds.get(i);
            if (thresholds != null) {
                candidates.add(ReplayCandidate.ofThresholds(proposalId + "-c" + i, thresholds));
            }
        }
        return selectBest(trajectory, candidates)
                .map(candidate -> RsiProposal.propose(proposalId, CandidateKind.THRESHOLD,
                        "decision.threshold", describe(candidate.thresholds()), 0));
    }

    /**
     * 劣化自动回滚：对已应用的提案（其回放候选）在新轨迹上重放，劣化/被拒即经安全平面回滚。
     *
     * @return 是否触发回滚（新轨迹上不再劣化返回 false）
     */
    public boolean rollbackIfDegraded(String proposalId, RsiSafetyPlane safetyPlane,
                                      Trajectory newTrajectory, ReplayCandidate appliedCandidate) {
        if (safetyPlane == null || newTrajectory == null || appliedCandidate == null) {
            return false;
        }
        BaselineComparison comparison = replayEngine.compare(newTrajectory, appliedCandidate);
        if (comparison.rejected() || comparison.verdict() == BaselineComparison.Verdict.DEGRADED) {
            return safetyPlane.rollback(proposalId);
        }
        return false;
    }

    private String describe(DecisionThresholds thresholds) {
        if (thresholds == null) {
            return "";
        }
        return "routing=" + thresholds.routing()
                + ",approvalAuto=" + thresholds.approvalAuto()
                + ",artifactAccept=" + thresholds.artifactAccept()
                + ",completion=" + thresholds.completion();
    }
}
