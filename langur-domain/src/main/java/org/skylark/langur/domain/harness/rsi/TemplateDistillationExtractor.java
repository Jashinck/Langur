package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.evaluation.Checksums;

import java.util.List;

/**
 * 确定性模板蒸馏抽取器（R2，M5/M6 提炼的离线兜底 / 缺省实现）。
 * <p>纯 JDK、零网络、零外部依赖（P1）：从轨迹<b>已录制信号</b>（成功与否 + 动作序列 + 判定分流）组装
 * 一条成功模式或失败教训文本，不调用大模型。稳定 id 由 {@code sha256(namespace|kind|content)} 前 16 位
 * 派生（幂等，供去重/UPSERT）。{@code LlmDistillationExtractor}（infra）可在其上做语义归纳（P5）。</p>
 */
public class TemplateDistillationExtractor implements DistillationExtractor {

    @Override
    public List<DistilledMemory> extract(Trajectory trajectory) {
        if (trajectory == null || trajectory.steps().isEmpty()) {
            return List.of();
        }
        List<TrajectoryStep> steps = trajectory.steps();
        String actions = String.join(" → ", steps.stream().map(TrajectoryStep::action)
                .filter(a -> a != null && !a.isBlank()).toList());
        String routes = trajectory.decisions().stream()
                .map(d -> d.key() + "=" + d.baselineRoute().name().toLowerCase())
                .distinct()
                .reduce((a, b) -> a + ", " + b).orElse("(无判定)");
        DistillKind kind = trajectory.success() ? DistillKind.SUCCESS_PATTERN : DistillKind.FAILURE_LESSON;
        String content = trajectory.success()
                ? "成功模式：任务 " + trajectory.taskId() + " 经 " + steps.size() + " 轮达成。"
                        + "动作序列：" + (actions.isBlank() ? "(空)" : actions)
                        + "；关键判定分流：" + routes + "。同类任务可复用该路径。"
                : "失败教训：任务 " + trajectory.taskId() + " 未成功。"
                        + "已执行动作序列：" + (actions.isBlank() ? "(空)" : actions)
                        + "；关键判定分流：" + routes + "。同类任务应避免重复该无效路径。";
        String id = "rsi-" + Checksums.sha256(DistilledMemory.DEFAULT_NAMESPACE, kind.name(), content)
                .substring(0, 16);
        return List.of(DistilledMemory.of(
                DistilledMemory.DEFAULT_NAMESPACE, id, content, 0.0, trajectory.taskId(), kind));
    }
}
