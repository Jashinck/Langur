package org.skylark.langur.infrastructure.harness.rsi;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.rsi.DistilledMemory;
import org.skylark.langur.domain.harness.rsi.DistillationExtractor;
import org.skylark.langur.domain.harness.rsi.DistillKind;
import org.skylark.langur.domain.harness.rsi.TemplateDistillationExtractor;
import org.skylark.langur.domain.harness.rsi.Trajectory;
import org.skylark.langur.domain.harness.rsi.TrajectoryStep;
import org.skylark.langur.domain.harness.evaluation.Checksums;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;

import java.util.List;

/**
 * M5/M6 大模型蒸馏抽取器（R2，infra）——经 {@link LlmGateway} {@link ModelRole#REASONING} 把轨迹归纳为
 * 一条语义化知识（成功模式或失败教训），覆盖模板抽取器的纯字符串组装。
 * <p>P10 降级：大模型异常 / 空响应 → 回退 {@link TemplateDistillationExtractor}（确定性模板，绝不产空）。
 * 经 {@code langur.rsi.distillation.llm-enabled=true} 择一装配（P5），缺省用模板（零网络、离线可测）。</p>
 */
@Slf4j
public class LlmDistillationExtractor implements DistillationExtractor {

    private static final String PROMPT_TEMPLATE =
            "你是 Agent 执行轨迹的复盘员。根据以下历史执行轨迹，提炼出一条可复用的知识（成功模式或失败教训），"
                    + "不超过 120 字，直接输出知识本身，不要任何前缀、编号或解释。\n"
                    + "轨迹是否成功：%s\n动作序列：%s\n关键判定分流：%s";

    private final LlmGateway llmGateway;
    private final TemplateDistillationExtractor fallback = new TemplateDistillationExtractor();

    public LlmDistillationExtractor(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public List<DistilledMemory> extract(Trajectory trajectory) {
        if (trajectory == null || trajectory.steps().isEmpty()) {
            return List.of();
        }
        if (llmGateway == null) {
            return fallback.extract(trajectory);
        }
        String actions = String.join(" → ", trajectory.steps().stream().map(TrajectoryStep::action)
                .filter(a -> a != null && !a.isBlank()).toList());
        String routes = trajectory.decisions().stream()
                .map(d -> d.key() + "=" + d.baselineRoute().name().toLowerCase())
                .distinct().reduce((a, b) -> a + ", " + b).orElse("(无判定)");
        String userMessage = String.format(PROMPT_TEMPLATE,
                trajectory.success() ? "是" : "否",
                actions.isBlank() ? "(空)" : actions,
                routes);
        try {
            String summary = llmGateway.complete(ModelRole.REASONING, "轨迹复盘", userMessage);
            if (summary == null || summary.isBlank()) {
                return fallback.extract(trajectory);
            }
            DistillKind kind = trajectory.success() ? DistillKind.SUCCESS_PATTERN : DistillKind.FAILURE_LESSON;
            String id = "rsi-" + Checksums.sha256(
                    DistilledMemory.DEFAULT_NAMESPACE, kind.name(), summary).substring(0, 16);
            return List.of(DistilledMemory.of(
                    DistilledMemory.DEFAULT_NAMESPACE, id, summary.strip(),
                    0.0, trajectory.taskId(), kind));
        } catch (RuntimeException e) {
            log.warn("[R2] LLM distillation failed, falling back to template: {}", e.getMessage());
            return fallback.extract(trajectory);
        }
    }
}
