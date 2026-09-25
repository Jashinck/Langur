package org.skylark.langur.infrastructure.harness.context.vector;

import org.skylark.langur.domain.harness.context.vector.VectorRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 组件 L4 - 客户端混合融合纯函数（H14.5/H14.6 共用，DD17/DD20）。
 * <p>ES（RRF retriever 属付费授权层，DD20 缺省关闭）与 Milvus（RESTful v2 无服务端 ranker 下推，DD16 偏差）
 * 的两通道召回结果均在此融合：RRF 免归一化、跨检索器稳健；WEIGHTED 供分数可比场景。无状态、可离线确定性单测。</p>
 */
public final class ClientSideFusion {

    private ClientSideFusion() {
    }

    /**
     * RRF 融合（DD17）：score(d) = Σ_lists 1 / (k + rank)，rank 从 1 起。
     * 并列时按 key 字典序保证确定性。
     */
    public static List<VectorRecord> fuseRrf(List<List<VectorRecord>> rankedLists, int rrfK, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, VectorRecord> firstSeen = new LinkedHashMap<>();
        for (List<VectorRecord> ranked : rankedLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                VectorRecord record = ranked.get(rank);
                String key = record.getNamespace() + "::" + record.getId();
                scores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
                firstSeen.putIfAbsent(key, record);
            }
        }
        return topByFusedScore(scores, firstSeen, topK);
    }

    /**
     * 加权融合（WEIGHTED）：各列表分数 min-max 归一后按 {@code lexicalWeight} 混合：
     * final = (1 - w) * normSemantic + w * normLexical；单值列表归一为 1.0。
     */
    public static List<VectorRecord> fuseWeighted(List<VectorRecord> lexical, List<VectorRecord> semantic,
                                                  double lexicalWeight, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, VectorRecord> firstSeen = new LinkedHashMap<>();
        accumulateNormalized(scores, firstSeen, semantic, 1.0 - lexicalWeight);
        accumulateNormalized(scores, firstSeen, lexical, lexicalWeight);
        return topByFusedScore(scores, firstSeen, topK);
    }

    private static void accumulateNormalized(Map<String, Double> scores, Map<String, VectorRecord> firstSeen,
                                             List<VectorRecord> ranked, double weight) {
        if (ranked.isEmpty()) {
            return;
        }
        double max = ranked.stream().mapToDouble(VectorRecord::getScore).max().orElse(0d);
        double min = ranked.stream().mapToDouble(VectorRecord::getScore).min().orElse(0d);
        double span = max - min;
        for (VectorRecord record : ranked) {
            String key = record.getNamespace() + "::" + record.getId();
            double normalized = span <= 0d ? 1.0 : (record.getScore() - min) / span;
            scores.merge(key, weight * normalized, Double::sum);
            firstSeen.putIfAbsent(key, record);
        }
    }

    private static List<VectorRecord> topByFusedScore(Map<String, Double> scores,
                                                      Map<String, VectorRecord> firstSeen, int topK) {
        List<VectorRecord> fused = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(topK, 0))
                .forEach(entry -> fused.add(firstSeen.get(entry.getKey()).toBuilder()
                        .score(entry.getValue())
                        .build()));
        return fused;
    }
}
