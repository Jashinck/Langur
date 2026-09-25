package org.skylark.langur.infrastructure.harness.context.vector;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 验收（M3 重排）- 混合重排按语义+词面覆盖重新排序，纠正纯向量分错配，并支持 topK 截断。
 */
class EmbeddingRerankPortTest {

    private final EmbeddingPort embedding = new LexicalEmbeddingPort();

    private RerankProperties props(double alpha, int topK) {
        RerankProperties p = new RerankProperties();
        p.setEnabled(true);
        p.setAlpha(alpha);
        p.setTopK(topK);
        return p;
    }

    private VectorRecord record(String id, String content, double incomingScore) {
        return VectorRecord.builder()
                .id(id).namespace("ns").content(content)
                .vector(embedding.embed(content))
                .metadata(Map.of())
                .score(incomingScore)
                .build();
    }

    @Test
    void shouldPromoteKeywordMatchingCandidateOverIncomingOrder() {
        // 入参顺序把不相关的 noise 放前面且给高分，重排应把命中查询词的 relevant 提到首位
        VectorRecord noise = record("noise", "recipe for chocolate cake", 0.99d);
        VectorRecord relevant = record("relevant", "reset the database password", 0.10d);

        List<VectorRecord> reranked = new EmbeddingRerankPort(embedding, props(0.7d, 0))
                .rerank("reset database password", List.of(noise, relevant), 0);

        assertEquals("relevant", reranked.get(0).getId(), "命中查询词者应被重排到首位");
        assertTrue(reranked.get(0).getScore() > reranked.get(1).getScore(), "重排后按相关性降序");
    }

    @Test
    void shouldApplyTopKTruncation() {
        List<VectorRecord> candidates = List.of(
                record("a", "reset database password", 0.5d),
                record("b", "reset database connection", 0.4d),
                record("c", "chocolate cake recipe", 0.3d));

        List<VectorRecord> reranked = new EmbeddingRerankPort(embedding, props(0.7d, 2))
                .rerank("reset database", candidates, 0);

        assertEquals(2, reranked.size(), "topK=2 应截断");
    }

    @Test
    void shouldHonorExplicitTopKArgument() {
        List<VectorRecord> candidates = List.of(
                record("a", "alpha content", 0.5d),
                record("b", "beta content", 0.4d));

        List<VectorRecord> reranked = new EmbeddingRerankPort(embedding, props(0.7d, 0))
                .rerank("alpha", candidates, 1);

        assertEquals(1, reranked.size());
    }

    @Test
    void shouldReturnEmptyForNoCandidates() {
        assertTrue(new EmbeddingRerankPort(embedding, props(0.7d, 0)).rerank("q", List.of(), 5).isEmpty());
    }
}
