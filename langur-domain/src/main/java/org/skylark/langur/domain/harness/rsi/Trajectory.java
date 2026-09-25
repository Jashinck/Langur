package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 执行轨迹（R0）。一次历史任务执行的可回放快照——按轮次的成本步骤（{@link TrajectoryStep}）+ 录制的 Jev 判定
 * （{@link RecordedDecision}）+ 原始执行结果（{@code success}）+ 当时生效的阈值组（{@code baselineThresholds}）。
 * <p>由 {@link TrajectoryRepository} 从轨迹仓库加载；{@link ReplayEngine} 在其上做确定性反事实重放。
 * 纯 JDK record，零外部依赖（P1）；列表构造时防御性拷贝为不可变、判定按轮次排序保证回放确定。</p>
 *
 * @param taskId             任务标识
 * @param success            原始执行是否成功（基线结果）
 * @param baselineThresholds 原始执行生效的置信阈值组（基线复现依据；空则取 DD11 缺省）
 * @param steps              按轮次的成本步骤（原始执行<b>实际跑过</b>的轮次）
 * @param decisions          录制的判定（按轮次升序）
 */
public record Trajectory(String taskId,
                         boolean success,
                         DecisionThresholds baselineThresholds,
                         List<TrajectoryStep> steps,
                         List<RecordedDecision> decisions) {

    public Trajectory {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Trajectory taskId must not be blank");
        }
        baselineThresholds = (baselineThresholds == null) ? DecisionThresholds.defaults() : baselineThresholds;
        steps = (steps == null) ? List.of() : List.copyOf(steps);
        decisions = (decisions == null) ? List.of() : List.copyOf(decisions);
    }

    /** 便捷工厂：判定按轮次升序、同轮按 key 稳定排序（回放确定性）。 */
    public static Trajectory of(String taskId,
                                boolean success,
                                DecisionThresholds baselineThresholds,
                                List<TrajectoryStep> steps,
                                List<RecordedDecision> decisions) {
        List<RecordedDecision> sorted = new ArrayList<>(decisions == null ? List.of() : decisions);
        sorted.sort(Comparator.comparingInt(RecordedDecision::round).thenComparing(RecordedDecision::key));
        return new Trajectory(taskId, success, baselineThresholds, steps, sorted);
    }

    /** 取指定轮次的成本步骤（回放据此扣减 SKIP/TERMINATE 成本）。 */
    public Optional<TrajectoryStep> stepAtRound(int round) {
        return steps.stream().filter(s -> s.round() == round).findFirst();
    }

    /** 最后一轮的轮次序号；无步骤返回 {@link Integer#MIN_VALUE}。 */
    public int lastRound() {
        return steps.stream().mapToInt(TrajectoryStep::round).max().orElse(Integer.MIN_VALUE);
    }
}
