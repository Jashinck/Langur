package org.skylark.langur.infrastructure.harness.context.vector;

import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.domain.harness.context.vector.RerankPort;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * C 组件 L4 - 混合重排实现（H2，M3）。
 * <p>对向量召回结果按 {@code alpha * 语义cosine + (1-alpha) * 词面覆盖} 混合打分重排去噪：
 * 语义项用当前 {@link EmbeddingPort} 重新计算查询-候选相似度，词面项补足精确关键词命中，
 * 相比纯向量分能纠正"语义相近但关键词错配"的排序。确定性、无外部依赖，可离线单测。</p>
 * <p>仅在 {@code langur.rerank.enabled=true} 时装配；缺省不重排。</p>
 */
@Component
@ConditionalOnProperty(name = "langur.rerank.enabled", havingValue = "true")
public class EmbeddingRerankPort implements RerankPort {

    private final EmbeddingPort embeddingPort;
    private final RerankProperties properties;

    public EmbeddingRerankPort(EmbeddingPort embeddingPort, RerankProperties properties) {
        this.embeddingPort = embeddingPort;
        this.properties = properties;
    }

    @Override
    public List<VectorRecord> rerank(String query, List<VectorRecord> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        float[] queryVector = embeddingPort.embed(query);
        Set<String> queryTokens = tokenize(query);
        double alpha = clampAlpha(properties.getAlpha());

        List<VectorRecord> scored = new ArrayList<>(candidates.size());
        for (VectorRecord candidate : candidates) {
            double semantic = VectorMath.cosine(queryVector, embeddingPort.embed(candidate.getContent()));
            double lexical = lexicalCoverage(queryTokens, tokenize(candidate.getContent()));
            double score = alpha * semantic + (1d - alpha) * lexical;
            scored.add(candidate.toBuilder().score(score).build());
        }
        scored.sort(Comparator.comparingDouble(VectorRecord::getScore).reversed());

        int limit = topK > 0 ? topK : properties.getTopK();
        if (limit > 0 && scored.size() > limit) {
            return new ArrayList<>(scored.subList(0, limit));
        }
        return scored;
    }

    private double clampAlpha(double alpha) {
        if (Double.isNaN(alpha)) {
            return 0.7d;
        }
        return Math.min(1d, Math.max(0d, alpha));
    }

    /** 词面覆盖：查询词元被候选命中的比例（0..1）。 */
    private double lexicalCoverage(Set<String> queryTokens, Set<String> candidateTokens) {
        if (queryTokens.isEmpty()) {
            return 0d;
        }
        long hit = queryTokens.stream().filter(candidateTokens::contains).count();
        return (double) hit / queryTokens.size();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(text.toLowerCase().split("[^\\p{L}\\p{N}]+")));
    }
}
