package org.skylark.langur.infrastructure.harness.rsi;

import org.skylark.langur.domain.harness.rsi.RecordedDecision;
import org.skylark.langur.domain.harness.rsi.Trajectory;
import org.skylark.langur.domain.harness.rsi.TrajectoryStep;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillStep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能自合成器（R3，RSI L3 枢纽）——从一组同类高频成功轨迹归纳<b>候选</b> {@link SkillSynthesisCandidate}。
 * <p>确定性模板归纳（离线零网络）：以首条成功轨迹的动作序列为范本，连续重复动作去重后逐个映射为
 * {@code TOOL_CALL} 步骤（动作标识即工具 ID）；若轨迹群含 {@code task-complete} 录制判定（J9 早停信号），
 * 末尾追加一个 J10 {@code DECISION} 步骤（noul 完成门，值+置信 ≥ 0.85 即 END）——把"临场语义判定"固化为
 * 确定性编排节点。M5 归纳（承接 2.0 语义化步骤）为后续增强，模板是缺省确定性基线（P10）。</p>
 * <p>诚实成本模型：{@code llmRounds}=DECISION 步骤数（执行时仅廉价判定），{@code baselineLlmRounds}=
 * 各来源轨迹轮次之和（原 ReAct 每轮一次推理）。纯 JDK + Lombok，无 Spring 依赖，可离线单测。</p>
 */
public class SkillSynthesizer {

    /** J9 早停判定键：命中则在技能末尾固化 DECISION 完成门（J10 增量）。 */
    private static final String COMPLETE_KEY = "task-complete";

    /**
     * 从同类轨迹归纳候选技能。
     *
     * @param name        技能名（工具 ID {@code skill:{name}}）
     * @param trajectories 同类任务历史轨迹（含成功与失败；仅成功轨迹参与归纳）
     * @return 候选；无成功轨迹可归纳时返回 {@link Optional#empty()}
     * @throws IllegalArgumentException name 为空、轨迹列表为 null/空
     */
    public Optional<SkillSynthesisCandidate> synthesize(String name, List<Trajectory> trajectories) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        if (trajectories == null || trajectories.isEmpty()) {
            throw new IllegalArgumentException("trajectories must not be empty");
        }
        List<Trajectory> successful = trajectories.stream().filter(Trajectory::success).toList();
        if (successful.isEmpty()) {
            return Optional.empty();
        }
        List<SkillStep> steps = new ArrayList<>();
        List<String> actions = canonicalActions(successful.get(0));
        for (int i = 0; i < actions.size(); i++) {
            steps.add(SkillStep.toolCall("step" + (i + 1), actions.get(i), Map.of()));
        }
        // J10 增量：轨迹群含 task-complete 录制判定 → 固化 DECISION 完成门
        Set<String> decisionKeys = successful.stream()
                .flatMap(t -> t.decisions().stream())
                .map(RecordedDecision::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int llmRounds = 0;
        if (decisionKeys.contains(COMPLETE_KEY)) {
            steps.add(SkillStep.decisionNoul("gate-complete",
                    "任务目标是否已达成、可安全结束（0-1，越高越已达成）", 0.85, Map.of(), "END", "step1"));
            llmRounds = 1;
        }
        List<String> sourceTaskIds = successful.stream().map(Trajectory::taskId).toList();
        int baselineLlmRounds = successful.stream().mapToInt(t -> t.steps().size()).sum();
        return Optional.of(SkillSynthesisCandidate.of(
                name,
                "自合成技能（源自 " + successful.size() + " 条成功轨迹）",
                "", "LOW", steps, sourceTaskIds, llmRounds, baselineLlmRounds));
    }

    private List<String> canonicalActions(Trajectory trajectory) {
        List<String> actions = new ArrayList<>();
        String previous = null;
        for (TrajectoryStep step : trajectory.steps()) {
            String action = step.action();
            if (action == null || action.isBlank() || action.equals(previous)) {
                continue;
            }
            actions.add(action);
            previous = action;
        }
        return actions;
    }
}
