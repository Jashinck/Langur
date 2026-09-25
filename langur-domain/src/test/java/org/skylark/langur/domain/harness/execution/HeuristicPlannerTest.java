package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 - 启发式规划器单测：序列标记拆解、单步退化、上限合并、空目标兜底（离线确定性）。
 */
class HeuristicPlannerTest {

    private final HeuristicPlanner planner = new HeuristicPlanner();

    private Agent agent() {
        return Agent.create(AgentConfig.defaultConfig("PlanAgent"));
    }

    @Test
    void shouldSplitOnSequenceConnectors() {
        List<PlanStep> steps = planner.plan("首先 采集数据 然后 清洗 最后 汇总", agent());

        assertEquals(3, steps.size());
        assertEquals("采集数据", steps.get(0).getThought());
        assertEquals("清洗", steps.get(1).getThought());
        assertEquals("汇总", steps.get(2).getThought());
        assertTrue(steps.stream().allMatch(s -> s.getStatus() == StepStatus.PENDING));
    }

    @Test
    void shouldSplitOnSemicolonsAndNewlines() {
        List<PlanStep> steps = planner.plan("分析需求; 设计接口；编写代码\n撰写测试", agent());

        assertEquals(4, steps.size());
    }

    @Test
    void shouldSplitOnNumberedMarkers() {
        List<PlanStep> steps = planner.plan("1. 甲 2. 乙 3. 丙", agent());

        assertEquals(3, steps.size());
        assertEquals("甲", steps.get(0).getThought());
    }

    @Test
    void shouldReturnSingleStepForPlainGoal() {
        List<PlanStep> steps = planner.plan("回答用户的问题", agent());

        assertEquals(1, steps.size());
        assertEquals("回答用户的问题", steps.get(0).getThought());
        assertEquals(0, steps.get(0).getIndex());
    }

    @Test
    void shouldFallbackForBlankOrNullGoal() {
        assertEquals("处理用户请求", planner.plan("   ", agent()).get(0).getThought());
        assertEquals("处理用户请求", planner.plan(null, agent()).get(0).getThought());
    }

    @Test
    void shouldCapStepsAndMergeOverflow() {
        StringBuilder goal = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            goal.append("步骤").append(i).append("; ");
        }

        List<PlanStep> steps = planner.plan(goal.toString(), agent());

        assertEquals(8, steps.size());
        assertTrue(steps.get(7).getThought().contains("步骤12"), "溢出部分应并入末步");
    }
}
