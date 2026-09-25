package org.skylark.langur.domain.harness.rsi;

import java.util.List;

/**
 * 蒸馏结果（R2）。一次轨迹蒸馏的完整判据——轨迹置信度、是否被低置信门拦下、接受的产物与拒绝清单。
 * <p>纯 JDK record，零外部依赖（P1）；列表构造时防御性拷贝为不可变视图。</p>
 *
 * @param taskId     来源任务标识
 * @param confidence 轨迹 Jev 置信度（录制判定置信均值；无判定则 1.0=无信号不拦）
 * @param gated      是否被低置信门拦下（true → {@code memories} 空、{@code rejects} 含低置信原因）
 * @param memories   通过门与去重后接受的产物
 * @param rejects    拒绝说明（低置信 / 重复 / 空轨迹 / 空内容）
 */
public record DistillationResult(String taskId,
                                 double confidence,
                                 boolean gated,
                                 List<DistilledMemory> memories,
                                 List<String> rejects) {

    public DistillationResult {
        memories = (memories == null) ? List.of() : List.copyOf(memories);
        rejects = (rejects == null) ? List.of() : List.copyOf(rejects);
    }

    /** 便捷工厂。 */
    public static DistillationResult of(String taskId, double confidence, boolean gated,
                                        List<DistilledMemory> memories, List<String> rejects) {
        return new DistillationResult(taskId, confidence, gated, memories, rejects);
    }
}
