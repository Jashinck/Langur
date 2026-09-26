package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.rsi.CandidateKind;
import org.skylark.langur.domain.harness.rsi.RsiProposal;
import org.skylark.langur.domain.harness.rsi.RsiProposalStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-G 提案仓库内存实现测试（无 Mockito）。
 * <p>覆盖：往返存取、缺失返回 empty、同 id 覆盖即版本演进、listAll 快照。</p>
 */
class InMemoryRsiProposalRepositoryTest {

    @Test
    void shouldRoundTripProposal() {
        InMemoryRsiProposalRepository repo = new InMemoryRsiProposalRepository();
        RsiProposal proposal = RsiProposal.propose("p1", CandidateKind.THRESHOLD, "decision.threshold.routing", "0.8", 0);

        repo.save(proposal);

        assertTrue(repo.findById("p1").isPresent());
        assertEquals("0.8", repo.findById("p1").get().payload());
    }

    @Test
    void shouldReturnEmptyForMissingOrNull() {
        InMemoryRsiProposalRepository repo = new InMemoryRsiProposalRepository();
        assertFalse(repo.findById("absent").isPresent());
        assertFalse(repo.findById(null).isPresent());
        assertTrue(repo.listAll().isEmpty());
    }

    @Test
    void shouldOverwriteSameIdAsVersionEvolution() {
        InMemoryRsiProposalRepository repo = new InMemoryRsiProposalRepository();
        repo.save(RsiProposal.propose("p1", CandidateKind.THRESHOLD, "t", "0.8", 0));
        repo.save(RsiProposal.propose("p1", CandidateKind.THRESHOLD, "t", "0.75", 0)
                .withStatus(RsiProposalStatus.VALIDATED));

        assertEquals(1, repo.listAll().size());
        assertEquals(RsiProposalStatus.VALIDATED, repo.findById("p1").get().status());
        assertEquals("0.75", repo.findById("p1").get().payload());
    }
}
