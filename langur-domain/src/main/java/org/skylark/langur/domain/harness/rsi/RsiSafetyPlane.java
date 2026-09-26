package org.skylark.langur.domain.harness.rsi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RSI 安全平面（R-G，P0，统辖决策平面）——提案-验证-应用三段式护栏。
 * <p>红线（P11/P12 编码进状态机）：① <b>权限隔离</b>——改动目标命中 {@code security.policy} / {@code validation.}
 * 前缀（安全策略层 / 四层校验链）的提案<b>提交即拒绝</b>（不可自改安全层）；② <b>只收紧不放松</b>——放松审批闸门
 * 的提案在回放验证阶段即被 R0 判 DEGRADED 拒绝；③ <b>高危人审</b>——策略级（THRESHOLD/ROUTE，{@link CandidateKind#isPolicyLevel()}）
 * 或命中 {@code critical} 的提案，验证通过后仍须 {@code humanApproved=true} 方可应用；④ <b>递归深度上限</b>——
 * 自改进链深度超 {@code maxDepth} 拒绝；⑤ <b>变更频率限流</b>——滑动窗口内提案数超 {@code maxProposalsPerMinute} 拒绝；
 * ⑥ <b>未经验证不得生效</b>——非 VALIDATED 状态不得 apply。</p>
 * <p>纯领域实现（无 Spring 依赖），由 start 层装配。告警由 {@link SafetyGateResult#reason()} 承载，
 * 由调用方经 H5 导出（R-G 组件本身不依赖日志框架，P1）。</p>
 */
public class RsiSafetyPlane {

    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final int DEFAULT_MAX_PROPOSALS_PER_MINUTE = 10;
    public static final long WINDOW_MILLIS = 60_000L;

    /** 权限隔离红线：禁止改动的目标前缀（SecurityPolicySPI / 四层校验链）。 */
    public static final List<String> DEFAULT_FORBIDDEN_TARGET_PREFIXES = List.of("security.policy", "validation.");

    private final RsiProposalRepository repository;
    private final int maxDepth;
    private final int maxProposalsPerMinute;
    private final Set<String> forbiddenTargetPrefixes;

    public RsiSafetyPlane(RsiProposalRepository repository) {
        this(repository, DEFAULT_MAX_DEPTH, DEFAULT_MAX_PROPOSALS_PER_MINUTE, DEFAULT_FORBIDDEN_TARGET_PREFIXES);
    }

    public RsiSafetyPlane(RsiProposalRepository repository, int maxDepth, int maxProposalsPerMinute,
                          List<String> forbiddenTargetPrefixes) {
        if (repository == null) {
            throw new IllegalArgumentException("RsiProposalRepository must not be null");
        }
        this.repository = repository;
        this.maxDepth = Math.max(0, maxDepth);
        this.maxProposalsPerMinute = Math.max(0, maxProposalsPerMinute);
        this.forbiddenTargetPrefixes = new LinkedHashSet<>(
                forbiddenTargetPrefixes == null ? List.of() : forbiddenTargetPrefixes);
    }

    /** 提交提案（PROPOSED）：红线目标 / 深度 / 频率 三道守门，通过即入库。 */
    public SafetyGateResult propose(RsiProposal proposal) {
        if (proposal == null) {
            return SafetyGateResult.rejected("null proposal");
        }
        for (String prefix : forbiddenTargetPrefixes) {
            if (proposal.target() != null && proposal.target().startsWith(prefix)) {
                return SafetyGateResult.rejected("forbidden target (security/validation layer): " + proposal.target());
            }
        }
        if (proposal.depth() > maxDepth) {
            return SafetyGateResult.rejected("recursion depth " + proposal.depth() + " exceeds max " + maxDepth);
        }
        long cutoff = proposal.createdAtMillis() - WINDOW_MILLIS;
        long recent = repository.listAll().stream()
                .filter(p -> p.createdAtMillis() >= cutoff)
                .count();
        if (recent >= maxProposalsPerMinute) {
            return SafetyGateResult.rejected("change frequency limit exceeded (max " + maxProposalsPerMinute + "/min)");
        }
        repository.save(proposal.withStatus(RsiProposalStatus.PROPOSED));
        return SafetyGateResult.allowed("proposed");
    }

    /** 回放验证（PROPOSED → VALIDATED/REJECTED）：劣化即拒绝，只收紧不放松（R0 裁定）。 */
    public SafetyGateResult validate(String proposalId, BaselineComparison comparison) {
        RsiProposal proposal = repository.findById(proposalId).orElse(null);
        if (proposal == null) {
            return SafetyGateResult.rejected("unknown proposal: " + proposalId);
        }
        if (proposal.status() != RsiProposalStatus.PROPOSED) {
            return SafetyGateResult.rejected("proposal not in PROPOSED state: " + proposal.status());
        }
        if (comparison == null) {
            return SafetyGateResult.rejected("missing replay comparison");
        }
        if (comparison.rejected() || comparison.verdict() == BaselineComparison.Verdict.DEGRADED) {
            repository.save(proposal.withStatus(RsiProposalStatus.REJECTED));
            return SafetyGateResult.rejected("degraded or safety-loosening: " + comparison.reason());
        }
        repository.save(proposal.withStatus(RsiProposalStatus.VALIDATED));
        return SafetyGateResult.allowed("validated");
    }

    /** 应用（VALIDATED → APPLIED）：高危提案强制人审；未经验证不得生效。 */
    public SafetyGateResult apply(String proposalId, boolean humanApproved) {
        RsiProposal proposal = repository.findById(proposalId).orElse(null);
        if (proposal == null) {
            return SafetyGateResult.rejected("unknown proposal: " + proposalId);
        }
        if (proposal.status() != RsiProposalStatus.VALIDATED) {
            return SafetyGateResult.rejected("proposal not validated (must pass replay first): " + proposal.status());
        }
        if (isHighRisk(proposal) && !humanApproved) {
            return SafetyGateResult.rejected("high-risk proposal requires human approval");
        }
        repository.save(proposal.withStatus(RsiProposalStatus.APPLIED));
        return SafetyGateResult.allowed("applied");
    }

    /** 一键回滚（APPLIED → ROLLED_BACK）；非 APPLIED 返回 false。 */
    public boolean rollback(String proposalId) {
        RsiProposal proposal = repository.findById(proposalId).orElse(null);
        if (proposal == null || proposal.status() != RsiProposalStatus.APPLIED) {
            return false;
        }
        repository.save(proposal.withStatus(RsiProposalStatus.ROLLED_BACK));
        return true;
    }

    private boolean isHighRisk(RsiProposal proposal) {
        return proposal.kind().isPolicyLevel()
                || (proposal.target() != null && proposal.target().toLowerCase().contains("critical"));
    }
}
