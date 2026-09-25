package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆自蒸馏器（R2，RSI L2）——离线把历史轨迹提炼为 L4 知识。
 * <p>流水线：① <b>Jev 置信门</b>（v3.0 增量）——以轨迹录制判定的置信均值作为可信度，低于
 * {@code minConfidence} 整条轨迹<b>不入蒸馏</b>（低置信轨迹不污染 L4）；② <b>M5/M6 提炼</b>——经
 * {@link DistillationExtractor} 端口（模板确定性兜底 / 大模型归纳，P3/P5）产出成功模式或失败教训；
 * ③ <b>去重</b>——写入前对目标命名空间做语义近邻检索，相似度 ≥ {@code dedupThreshold} 判重复拒绝；
 * ④ <b>写入</b>——经 {@link VectorMemoryService} UPSERT 进 L4，namespace 隔离（缺省 {@code rsi-distilled}，
 * 与业务 {@code knowledge} 隔离），metadata 携带 {@code confidence} 供召回降权。</p>
 * <p>纯领域实现（无 Spring 依赖），由 start 层装配。R2 为 RSI 自改进提供"经验沉淀"底座，
 * 与 R0（回放验证）/R1（反思）并列 N3；产物仍是候选提案语义，不直接改变执行策略（P11）。</p>
 */
public class MemoryDistiller {

    /** 缺省 Jev 置信门阈值：对齐决策平面 completion 维 DD11 经验值。 */
    public static final double DEFAULT_MIN_CONFIDENCE = 0.85;

    /** 缺省语义去重阈值：cosine 相似度达此值视为重复。 */
    public static final double DEFAULT_DEDUP_THRESHOLD = 0.95;

    private final DistillationExtractor extractor;
    private final double minConfidence;
    private final double dedupThreshold;
    private final String namespace;

    public MemoryDistiller(DistillationExtractor extractor) {
        this(extractor, DEFAULT_MIN_CONFIDENCE, DEFAULT_DEDUP_THRESHOLD, DistilledMemory.DEFAULT_NAMESPACE);
    }

    public MemoryDistiller(DistillationExtractor extractor, double minConfidence, double dedupThreshold) {
        this(extractor, minConfidence, dedupThreshold, DistilledMemory.DEFAULT_NAMESPACE);
    }

    public MemoryDistiller(DistillationExtractor extractor, double minConfidence, double dedupThreshold,
                           String namespace) {
        if (extractor == null) {
            throw new IllegalArgumentException("DistillationExtractor must not be null");
        }
        this.extractor = extractor;
        this.minConfidence = minConfidence;
        this.dedupThreshold = dedupThreshold;
        this.namespace = (namespace == null || namespace.isBlank())
                ? DistilledMemory.DEFAULT_NAMESPACE : namespace;
    }

    /** 轨迹 Jev 置信度：录制判定答案置信均值；无判定（或全空答案）返回 1.0（无信号 → 不拦，P10 诚实标注）。 */
    public double trajectoryConfidence(Trajectory trajectory) {
        if (trajectory == null) {
            return 0.0;
        }
        int count = 0;
        double sum = 0.0;
        for (RecordedDecision decision : trajectory.decisions()) {
            DecisionAnswer answer = decision.answer();
            if (answer != null) {
                sum += Math.max(0.0, Math.min(1.0, answer.confidence()));
                count++;
            }
        }
        return count == 0 ? 1.0 : sum / count;
    }

    /**
     * 纯蒸馏（无存储）：Jev 置信门 + 提炼 + 置信注入。不做语义去重（需存储），供单元测试与调用方自行落库。
     *
     * @return 门拦下 → {@code gated=true, memories=[]}；无内容 → {@code memories=[]} + 拒绝原因
     */
    public DistillationResult distill(Trajectory trajectory) {
        if (trajectory == null) {
            return DistillationResult.of("", 0.0, true, List.of(), List.of("EMPTY_TRAJECTORY"));
        }
        double confidence = trajectoryConfidence(trajectory);
        if (confidence < minConfidence) {
            return DistillationResult.of(trajectory.taskId(), confidence, true, List.of(),
                    List.of("LOW_CONFIDENCE:" + confidence + "<" + minConfidence));
        }
        List<DistilledMemory> extracted = extractor.extract(trajectory);
        if (extracted == null || extracted.isEmpty()) {
            return DistillationResult.of(trajectory.taskId(), confidence, false, List.of(),
                    List.of("NO_EXTRACTABLE_CONTENT"));
        }
        List<DistilledMemory> memories = extracted.stream()
                .map(m -> m.withNamespace(namespace).withConfidence(confidence))
                .toList();
        return DistillationResult.of(trajectory.taskId(), confidence, false, memories, List.of());
    }

    /**
     * 蒸馏并落库（含语义去重）：先 {@link #distill}，再对每条产物做目标命名空间近邻检索，
     * 相似度 ≥ {@code dedupThreshold} 判重复拒绝；通过者经 {@link VectorMemoryService#remember} 写入 L4。
     *
     * @return 落库后的结果（{@code rejects} 含 DUPLICATE 原因，{@code memories} 为实际写入的产物）
     */
    public DistillationResult distillAndStore(Trajectory trajectory, VectorMemoryService vectorMemoryService) {
        DistillationResult distilled = distill(trajectory);
        if (distilled.gated() || distilled.memories().isEmpty()) {
            return distilled;
        }
        if (vectorMemoryService == null) {
            return DistillationResult.of(distilled.taskId(), distilled.confidence(), false, List.of(),
                    List.of("NO_VECTOR_STORE"));
        }
        List<DistilledMemory> accepted = new ArrayList<>();
        List<String> rejects = new ArrayList<>(distilled.rejects());
        for (DistilledMemory memory : distilled.memories()) {
            if (isDuplicate(memory, vectorMemoryService)) {
                rejects.add("DUPLICATE:" + memory.id());
                continue;
            }
            vectorMemoryService.remember(memory.namespace(), memory.id(), memory.content(),
                    Map.of("source", "rsi-distillation",
                            "kind", memory.kind().name(),
                            "confidence", memory.confidence(),
                            "taskId", memory.sourceTaskId()));
            accepted.add(memory);
        }
        return DistillationResult.of(distilled.taskId(), distilled.confidence(),
                distilled.gated(), accepted, List.copyOf(rejects));
    }

    private boolean isDuplicate(DistilledMemory memory, VectorMemoryService vectorMemoryService) {
        try {
            List<VectorRecord> matches = vectorMemoryService.recall(
                    memory.namespace(), memory.content(), 1, 0L);
            return !matches.isEmpty() && matches.get(0).getScore() >= dedupThreshold;
        } catch (RuntimeException e) {
            // P10：去重检索异常 → 不阻断写入（去重是质量门而非安全门，降级放行）
            return false;
        }
    }
}
