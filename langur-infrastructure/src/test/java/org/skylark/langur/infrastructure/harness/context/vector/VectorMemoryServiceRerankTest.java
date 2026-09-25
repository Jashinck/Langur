package org.skylark.langur.infrastructure.harness.context.vector;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.RerankPort;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H2 验收（服务集成）- VectorMemoryService 装配 RerankPort 后，召回顺序由重排决定（此处为自然序的反转）。
 */
class VectorMemoryServiceRerankTest {

    private static final String NS = "tenant-a";

    @Test
    void shouldDelegateToRerankPortWhenWired() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        LexicalEmbeddingPort embedding = new LexicalEmbeddingPort();

        VectorMemoryService plain = new VectorMemoryService(embedding, store);
        plain.remember(NS, "k1", "reset the database password", Map.of());
        plain.remember(NS, "k2", "reset the database connection pool", Map.of());

        List<String> naturalOrder = ids(plain.recall(NS, "reset database", 5, 0));

        VectorMemoryService reranked = new VectorMemoryService(embedding, store, new ReversingRerank());
        List<String> rerankedOrder = ids(reranked.recall(NS, "reset database", 5, 0));

        List<String> expected = new ArrayList<>(naturalOrder);
        Collections.reverse(expected);
        assertEquals(expected, rerankedOrder, "重排应反转自然 cosine 顺序，证明服务委派了 RerankPort");
    }

    private List<String> ids(List<VectorRecord> records) {
        return records.stream().map(VectorRecord::getId).toList();
    }

    /** 反转候选顺序，确定性地证明重排生效。 */
    private static class ReversingRerank implements RerankPort {
        @Override
        public List<VectorRecord> rerank(String query, List<VectorRecord> candidates, int topK) {
            List<VectorRecord> reversed = new ArrayList<>(candidates);
            Collections.reverse(reversed);
            return reversed;
        }
    }
}
