package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.evaluation.Checksums;

/**
 * RSI 提案（R-G 安全平面，DD6 {@code t_rsi_proposal}）。自改进产物统一以提案承载——<b>默认候选、
 * 禁止直接生效</b>（P11），须经 R-G 三段式（验证 + 高危人审 + 灰度）方可应用。
 * <p>承载类别（复用 R0 {@link CandidateKind}）、改动目标（{@code target}）、内容（{@code payload}）、
 * 递归深度（{@code depth}，自改进之自改进的深度上限）、状态、审计校验和与创建时间。
 * 纯 JDK record，零外部依赖（P1）；构造时收敛缺省、校验和经 {@link Checksums#sha256} 派生（缺省）。</p>
 *
 * @param id              提案标识（版本化仓库主键）
 * @param kind            候选类别（THRESHOLD/ROUTE/PROMPT/SKILL/PARAMS…）
 * @param target          改动目标（如 {@code decision.threshold.routing}、{@code security.policy}）
 * @param payload         提案内容（阈值/路由/模板文本）
 * @param depth           递归深度（自改进链深度，上限由 R-G 控制）
 * @param status          生命周期状态
 * @param checksum        SHA-256 审计校验和（缺省由要素派生）
 * @param createdAtMillis 创建时间戳（频率限流窗口依据）
 */
public record RsiProposal(String id,
                          CandidateKind kind,
                          String target,
                          String payload,
                          int depth,
                          RsiProposalStatus status,
                          String checksum,
                          long createdAtMillis) {

    public RsiProposal {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RsiProposal id must not be blank");
        }
        kind = (kind == null) ? CandidateKind.PARAMS : kind;
        target = (target == null) ? "" : target;
        payload = (payload == null) ? "" : payload;
        depth = Math.max(0, depth);
        status = (status == null) ? RsiProposalStatus.PROPOSED : status;
        createdAtMillis = Math.max(0L, createdAtMillis);
        checksum = (checksum == null || checksum.isBlank())
                ? Checksums.sha256(id, kind.name(), target, payload)
                : checksum;
    }

    /** 便捷工厂：以当前时间创建 PROPOSED 提案。 */
    public static RsiProposal propose(String id, CandidateKind kind, String target, String payload, int depth) {
        return new RsiProposal(id, kind, target, payload, depth, RsiProposalStatus.PROPOSED, null,
                System.currentTimeMillis());
    }

    /** 以指定时间戳创建 PROPOSED 提案（离线确定性测试/重放用）。 */
    public static RsiProposal propose(String id, CandidateKind kind, String target, String payload,
                                      int depth, long createdAtMillis) {
        return new RsiProposal(id, kind, target, payload, depth, RsiProposalStatus.PROPOSED, null,
                createdAtMillis);
    }

    /** 状态迁移（保持 id/校验和/时间戳不变）。 */
    public RsiProposal withStatus(RsiProposalStatus newStatus) {
        return new RsiProposal(id, kind, target, payload, depth, newStatus, checksum, createdAtMillis);
    }
}
