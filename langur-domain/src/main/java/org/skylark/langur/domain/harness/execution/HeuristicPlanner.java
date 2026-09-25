package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 启发式规划器（H3 默认实现）- 零外部依赖、离线确定性可测（P1/P10）。
 * <p>按显式序列标记（分号/换行/「首先·然后·接着·最后」/「第 N 步」/「1. 2.」）将目标拆解为有序子步骤；
 * 无可拆解标记时退化为单步，保证 {@link #plan} 恒返回非空。作为 LLM 规划不可用时的兜底。</p>
 */
public class HeuristicPlanner implements Planner {

    /** 子步骤数量上限，超出部分并入末步，避免规划爆炸撑穿闸门。 */
    private static final int MAX_STEPS = 8;

    /** 序列切分：分号 / 换行 / 中英文序列连接词 / 「第 N 步」 / 有序列表编号。 */
    private static final Pattern SPLIT = Pattern.compile(
            "[;；\\n]+|首先|其次|然后|接着|之后|随后|再者|最后|第[一二三四五六七八九十百0-9]+步|\\d+[.)、]");

    @Override
    public List<PlanStep> plan(String goal, Agent agent) {
        List<String> fragments = split(goal);
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            steps.add(toStep(i, fragments.get(i)));
        }
        if (steps.isEmpty()) {
            steps.add(toStep(0, goal == null || goal.isBlank() ? "处理用户请求" : goal.trim()));
        }
        return steps;
    }

    private List<String> split(String goal) {
        List<String> fragments = new ArrayList<>();
        if (goal == null || goal.isBlank()) {
            return fragments;
        }
        StringBuilder overflow = new StringBuilder();
        for (String raw : SPLIT.split(goal)) {
            String piece = raw == null ? "" : raw.trim();
            if (piece.isEmpty()) {
                continue;
            }
            if (fragments.size() < MAX_STEPS) {
                fragments.add(piece);
            } else {
                if (overflow.length() > 0) {
                    overflow.append(' ');
                }
                overflow.append(piece);
            }
        }
        if (overflow.length() > 0) {
            if (fragments.isEmpty()) {
                fragments.add(overflow.toString().trim());
            } else {
                int last = fragments.size() - 1;
                fragments.set(last, fragments.get(last) + " " + overflow.toString().trim());
            }
        }
        return fragments;
    }

    private PlanStep toStep(int index, String subGoal) {
        return PlanStep.builder()
                .index(index)
                .thought(subGoal)
                .action("react")
                .status(StepStatus.PENDING)
                .build();
    }
}
