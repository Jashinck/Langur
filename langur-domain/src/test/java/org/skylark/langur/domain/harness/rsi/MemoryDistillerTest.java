package org.skylark.langur.domain.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 记忆自蒸馏器离线确定性测试（无 Mockito，匿名桩实现 EmbeddingPort/VectorStore）。
 * <p>覆盖：成功轨迹提炼成功模式、失败轨迹提炼失败教训、Jev 置信门拦下低置信轨迹、无判定视为置信 1.0、
 * 空/空轨迹拒绝、语义去重拒绝重复、命名空间隔离、入参守卫。</p>
 */
class MemoryDistillerTest {

    private static Trajectory trajectory(boolean success, List<DecisionAnswer> answers) {
        List<RecordedDecision> decisions = new ArrayList<>();
        int round = 1;
        for (DecisionAnswer answer : answers) {
            decisions.add(new RecordedDecision("d" + round, round, answer,
                    ThresholdCategory.ROUTING, ReplayRoute.RUN, 5L));
            round++;
        }
        return Trajectory.of("task-1", success, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "search", 100, 10),
                        TrajectoryStep.of(2, "answer", 50, 5)),
                decisions);
    }

    @Test
    void shouldDistillSuccessPatternForSuccessfulTrajectory() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());
        Trajectory t = trajectory(true, List.of(DecisionAnswer.ofProbability(0.9, 0.9)));

        DistillationResult result = distiller.distill(t);

        assertFalse(result.gated());
        assertEquals(0.9, result.confidence(), 1e-9);
        assertEquals(1, result.memories().size());
        DistilledMemory m = result.memories().get(0);
        assertEquals(DistillKind.SUCCESS_PATTERN, m.kind());
        assertEquals("task-1", m.sourceTaskId());
        assertEquals(0.9, m.confidence(), 1e-9);
        assertTrue(m.content().contains("成功模式"));
        assertNotNull(m.id());
    }

    @Test
    void shouldDistillFailureLessonForFailedTrajectory() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());
        Trajectory t = trajectory(false, List.of(DecisionAnswer.ofProbability(0.8, 0.95)));

        DistillationResult result = distiller.distill(t);

        assertFalse(result.gated());
        assertEquals(1, result.memories().size());
        assertEquals(DistillKind.FAILURE_LESSON, result.memories().get(0).kind());
        assertTrue(result.memories().get(0).content().contains("失败教训"));
    }

    @Test
    void shouldGateLowConfidenceTrajectory() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());
        Trajectory t = trajectory(true, List.of(
                DecisionAnswer.ofProbability(0.9, 0.5),
                DecisionAnswer.ofProbability(0.9, 0.6)));

        DistillationResult result = distiller.distill(t);

        assertTrue(result.gated(), "低置信轨迹应被 Jev 置信门拦下");
        assertTrue(result.memories().isEmpty());
        assertTrue(result.rejects().stream().anyMatch(r -> r.startsWith("LOW_CONFIDENCE")), result.rejects().toString());
    }

    @Test
    void shouldTreatNoDecisionsAsConfidenceOne() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());
        Trajectory t = trajectory(true, List.of());

        DistillationResult result = distiller.distill(t);

        assertEquals(1.0, result.confidence(), 1e-9);
        assertFalse(result.gated(), "无判定信号 → 不拦（诚实标注置信 1.0）");
    }

    @Test
    void shouldRejectEmptyTrajectory() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());
        Trajectory empty = Trajectory.of("t", true, DecisionThresholds.defaults(), List.of(), List.of());

        DistillationResult result = distiller.distill(empty);

        assertFalse(result.gated());
        assertTrue(result.memories().isEmpty());
        assertTrue(result.rejects().contains("NO_EXTRACTABLE_CONTENT"), result.rejects().toString());
    }

    @Test
    void shouldRejectNullTrajectory() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());

        DistillationResult result = distiller.distill(null);

        assertTrue(result.gated());
        assertTrue(result.rejects().contains("EMPTY_TRAJECTORY"), result.rejects().toString());
    }

    @Test
    void shouldStoreAndRejectDuplicate() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor());
        VectorMemoryService store = new VectorMemoryService(new BagEmbedding(), new MapVectorStore());
        Trajectory t = trajectory(true, List.of(DecisionAnswer.ofProbability(0.9, 0.9)));

        DistillationResult first = distiller.distillAndStore(t, store);
        DistillationResult second = distiller.distillAndStore(t, store);

        assertEquals(1, first.memories().size(), "首次应写入");
        assertTrue(second.memories().isEmpty(), "同源同内容应被语义去重拒绝");
        assertTrue(second.rejects().stream().anyMatch(r -> r.startsWith("DUPLICATE")), second.rejects().toString());
    }

    @Test
    void shouldApplyConfiguredNamespace() {
        MemoryDistiller distiller = new MemoryDistiller(new TemplateDistillationExtractor(),
                MemoryDistiller.DEFAULT_MIN_CONFIDENCE, MemoryDistiller.DEFAULT_DEDUP_THRESHOLD, "custom-ns");
        Trajectory t = trajectory(true, List.of(DecisionAnswer.ofProbability(0.9, 0.9)));

        DistillationResult result = distiller.distill(t);

        assertEquals("custom-ns", result.memories().get(0).namespace());
    }

    @Test
    void shouldRejectNullExtractor() {
        try {
            new MemoryDistiller(null);
            org.junit.jupiter.api.Assertions.fail("应拒绝空抽取器");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Extractor"), expected.getMessage());
        }
    }

    /** 确定性词袋嵌入：128 维字符频次，同文余弦=1.0。 */
    static final class BagEmbedding implements EmbeddingPort {
        @Override
        public float[] embed(String text) {
            float[] vector = new float[128];
            if (text != null) {
                for (char c : text.toCharArray()) {
                    if (c < 128) {
                        vector[c]++;
                    }
                }
            }
            double norm = 0.0;
            for (float v : vector) {
                norm += v * v;
            }
            if (norm > 0) {
                double scale = Math.sqrt(norm);
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) (vector[i] / scale);
                }
            }
            return vector;
        }

        @Override
        public int dimensions() {
            return 128;
        }
    }

    /** 内存向量存储：cosine 召回。 */
    static final class MapVectorStore implements VectorStore {
        private final List<VectorRecord> records = new ArrayList<>();

        @Override
        public void upsert(VectorRecord record) {
            records.removeIf(r -> r.getId().equals(record.getId()) && r.getNamespace().equals(record.getNamespace()));
            records.add(record);
        }

        @Override
        public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
            List<VectorRecord> hits = new ArrayList<>();
            for (VectorRecord record : records) {
                if (!record.getNamespace().equals(namespace)) {
                    continue;
                }
                double score = cosine(queryVector, record.getVector());
                hits.add(VectorRecord.builder()
                        .id(record.getId()).namespace(record.getNamespace())
                        .vector(record.getVector()).content(record.getContent())
                        .metadata(new HashMap<>(record.getMetadata()))
                        .score(score).build());
            }
            hits.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return hits.size() > topK ? hits.subList(0, topK) : hits;
        }

        private double cosine(float[] a, float[] b) {
            if (a == null || b == null || a.length != b.length) {
                return 0.0;
            }
            double dot = 0.0, na = 0.0, nb = 0.0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            if (na == 0.0 || nb == 0.0) {
                return 0.0;
            }
            return dot / (Math.sqrt(na) * Math.sqrt(nb));
        }
    }
}
