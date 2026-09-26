package org.skylark.langur.infrastructure.harness.rsi;

import org.skylark.langur.domain.harness.rsi.RsiProposal;
import org.skylark.langur.domain.harness.rsi.RsiProposalRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RSI 提案仓库内存实现（R-G，缺省装配）。按 id 覆盖存储（同 id 即版本演进），
 * {@code ConcurrentHashMap} 线程安全、离线确定性、零外部依赖；可被 JPA 实现覆盖（P5 开闭）。</p>
 */
public class InMemoryRsiProposalRepository implements RsiProposalRepository {

    private final ConcurrentMap<String, RsiProposal> proposals = new ConcurrentHashMap<>();

    @Override
    public void save(RsiProposal proposal) {
        if (proposal != null) {
            proposals.put(proposal.id(), proposal);
        }
    }

    @Override
    public Optional<RsiProposal> findById(String id) {
        return Optional.ofNullable(id == null ? null : proposals.get(id));
    }

    @Override
    public List<RsiProposal> listAll() {
        return List.copyOf(proposals.values());
    }
}
