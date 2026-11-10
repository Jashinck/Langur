package org.skylark.langur.domain.harness.rsi;

/**
 * 蒸馏知识产物（R2）。一条待写入 L4 的知识候选——承载命名空间隔离、稳定 id、原文、
 * 轨迹置信度（Jev 信号，供召回降权）与来源任务。
 * <p>纯 JDK record，零外部依赖（P1）。构造时防御性收敛：namespace 缺省 {@value #DEFAULT_NAMESPACE}、
 * confidence 钳制到 [0,1]、blank id/content 拒绝（蒸馏产物不得为空）。</p>
 *
 * @param namespace    知识命名空间（与既有 {@code knowledge} 隔离，防污染业务知识库）
 * @param id           稳定 id（同源同内容幂等，供去重/UPSERT）
 * @param content      知识原文（成功模式或失败教训）
 * @param confidence   来源轨迹的 Jev 置信度（0..1，召回降权权重；R2 增量）
 * @param sourceTaskId 来源任务标识（溯源）
 * @param kind         类别（成功模式 / 失败教训）
 */
public record DistilledMemory(String namespace,
                              String id,
                              String content,
                              double confidence,
                              String sourceTaskId,
                              DistillKind kind) {

    /** 缺省蒸馏命名空间（与业务 {@code knowledge} 隔离，namespace 隔离红线）。 */
    public static final String DEFAULT_NAMESPACE = "rsi-distilled";

    public DistilledMemory {
        namespace = (namespace == null || namespace.isBlank()) ? DEFAULT_NAMESPACE : namespace;
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("DistilledMemory id must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("DistilledMemory content must not be blank");
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        sourceTaskId = (sourceTaskId == null) ? "" : sourceTaskId;
        kind = (kind == null) ? DistillKind.SUCCESS_PATTERN : kind;
    }

    /** 便捷工厂。 */
    public static DistilledMemory of(String namespace, String id, String content,
                                     double confidence, String sourceTaskId, DistillKind kind) {
        return new DistilledMemory(namespace, id, content, confidence, sourceTaskId, kind);
    }

    /** 以轨迹置信度改写本产物（蒸馏器在 gate 后统一注入 Jev 置信信号）。 */
    public DistilledMemory withConfidence(double trajectoryConfidence) {
        return new DistilledMemory(namespace, id, content, trajectoryConfidence, sourceTaskId, kind);
    }

    /** 以目标命名空间改写本产物（蒸馏器统一应用配置命名空间，覆盖抽取器缺省）。 */
    public DistilledMemory withNamespace(String targetNamespace) {
        return new DistilledMemory(targetNamespace, id, content, confidence, sourceTaskId, kind);
    }
}
