package org.skylark.langur.domain.harness.rsi;

import java.util.List;
import java.util.Optional;

/**
 * RSI 提案仓库端口（R-G，DD6 {@code t_rsi_proposal}）。版本化存储提案与状态迁移，支撑审计链与一键回滚。
 * <p>domain 端口（P3 依赖倒置）；infra 提供内存/JPA 实现（R-G 缺省内存，离线确定性、无外部依赖）。
 * 缺省实现按 id 覆盖（同 id 即版本演进，审计靠 checksum + 状态链）。</p>
 */
public interface RsiProposalRepository {

    /** 保存/覆盖一条提案（状态迁移即覆盖）。 */
    void save(RsiProposal proposal);

    /** 按 id 加载；不存在返回 {@link Optional#empty()}。 */
    Optional<RsiProposal> findById(String id);

    /** 快照式列出全部提案（供审计/限流统计）。 */
    List<RsiProposal> listAll();
}
