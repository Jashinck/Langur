package org.skylark.langur.infrastructure.harness.context.vector;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H14.5/H14.6 验收 - {@link ClientSideFusion} 融合纯函数离线单测（DD17/DD20）：
 * RRF 排名融合确定性、topK 截断、WEIGHTED min-max 归一加权。ES/Milvus 客户端融合共用。
 */
class ClientSideFusionTest {

    @Test
    void shouldComputeRrfFusionDeterministically() {
        List<VectorRecord> lexical = List.of(record("ns", "A"), record("ns", "B"));
        List<VectorRecord> semantic = List.of(record("ns", "B"), record("ns", "C"));

        List<VectorRecord> fused = ClientSideFusion.fuseRrf(List.of(lexical, semantic), 60, 10);

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList(),
                "RRF：B=1/62+1/61 > A=1/61 > C=1/62");
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(0).getScore(), 1e-9);
        assertEquals(1.0 / 61, fused.get(1).getScore(), 1e-9);
        assertEquals(1.0 / 62, fused.get(2).getScore(), 1e-9);
        assertEquals(2, ClientSideFusion.fuseRrf(List.of(lexical, semantic), 60, 2).size(),
                "topK 截断");
    }

    @Test
    void shouldComputeWeightedFusionWithMinMaxNormalization() {
        List<VectorRecord> lexical = List.of(
                record("ns", "A").toBuilder().score(2.0).build(),
                record("ns", "B").toBuilder().score(1.0).build());
        List<VectorRecord> semantic = List.of(
                record("ns", "B").toBuilder().score(0.9).build(),
                record("ns", "C").toBuilder().score(0.1).build());

        List<VectorRecord> fused = ClientSideFusion.fuseWeighted(lexical, semantic, 0.3, 10);

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList());
        assertEquals(0.7, fused.get(0).getScore(), 1e-9, "B = 0.7*1.0(语义归一) + 0.3*0.0(词面归一)");
        assertEquals(0.3, fused.get(1).getScore(), 1e-9);
        assertEquals(0.0, fused.get(2).getScore(), 1e-9);
    }

    @Test
    void shouldReturnEmptyWhenNoCandidates() {
        assertEquals(List.of(), ClientSideFusion.fuseRrf(List.of(List.of(), List.of()), 60, 10));
        assertEquals(List.of(), ClientSideFusion.fuseWeighted(List.of(), List.of(), 0.3, 10));
    }

    private static VectorRecord record(String namespace, String id) {
        return VectorRecord.builder()
                .id(id).namespace(namespace).content(id)
                .metadata(Map.of())
                .build();
    }
}
