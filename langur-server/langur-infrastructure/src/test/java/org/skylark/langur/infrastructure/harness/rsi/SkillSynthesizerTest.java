package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.harness.rsi.RecordedDecision;
import org.skylark.langur.domain.harness.rsi.ReplayRoute;
import org.skylark.langur.domain.harness.rsi.ThresholdCategory;
import org.skylark.langur.domain.harness.rsi.Trajectory;
import org.skylark.langur.domain.harness.rsi.TrajectoryStep;
import org.skylark.langur.infrastructure.harness.tool.skill.StepType;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3 技能自合成器离线确定性测试（无 Mockito）。
 * <p>覆盖：成功轨迹群归纳 TOOL_CALL 序列、连续重复去重、task-complete 判定→DECISION 完成门（J10）、
 * 成本模型（llmRounds≤baseline）、全失败返回 empty、入参守卫。</p>
 */
class SkillSynthesizerTest {

    private final SkillSynthesizer synthesizer = new SkillSynthesizer();

    private static Trajectory successWithComplete(String taskId) {
        return Trajectory.of(taskId, true, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "search", 100, 10),
                        TrajectoryStep.of(2, "search", 90, 9),
                        TrajectoryStep.of(3, "answer", 50, 5)),
                List.of(new RecordedDecision("task-complete", 3, DecisionAnswer.ofProbability(0.9, 0.9),
                        ThresholdCategory.COMPLETION, ReplayRoute.RUN, 5L)));
    }

    @Test
    void shouldSynthesizeToolCallSequenceFromSuccessfulTrajectories() {
        Optional<SkillSynthesisCandidate> result = synthesizer.synthesize("research",
                List.of(successWithComplete("t1"), successWithComplete("t2")));

        assertTrue(result.isPresent());
        SkillSynthesisCandidate candidate = result.get();
        assertEquals("research", candidate.name());
        // 连续重复 search 去重 → [search, answer] 两个 TOOL_CALL
        assertEquals(2, candidate.steps().stream().filter(s -> s.getType() == StepType.TOOL_CALL).count());
        assertEquals("search", candidate.steps().get(0).getToolId());
        assertEquals("answer", candidate.steps().get(1).getToolId());
        assertEquals(List.of("t1", "t2"), candidate.sourceTaskIds());
        assertTrue(candidate.toSkillSpec().toolId().equals("skill:research"));
    }

    @Test
    void shouldEmitDecisionStepWhenTaskCompleteRecorded() {
        Optional<SkillSynthesisCandidate> result = synthesizer.synthesize("research",
                List.of(successWithComplete("t1")));

        assertTrue(result.isPresent());
        SkillSynthesisCandidate candidate = result.get();
        assertTrue(candidate.steps().stream().anyMatch(s -> s.getType() == StepType.DECISION),
                "task-complete 判定应固化为 DECISION 完成门（J10 增量）");
        assertEquals(1, candidate.llmRounds(), "仅一条 DECISION 判定");
        assertEquals(3, candidate.baselineLlmRounds(), "原 ReAct 3 轮推理");
    }

    @Test
    void shouldReturnEmptyWhenAllTrajectoriesFailed() {
        Trajectory failed = Trajectory.of("t", false, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "search", 100, 10)), List.of());

        assertTrue(synthesizer.synthesize("x", List.of(failed)).isEmpty(), "无成功轨迹 → 无可合成等价技能");
    }

    @Test
    void shouldRejectBlankNameAndEmptyTrajectories() {
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(" ", List.of(successWithComplete("t1"))));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize("x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize("x", null));
    }

    @Test
    void shouldNotBeWorseThanBaseline() {
        Optional<SkillSynthesisCandidate> result = synthesizer.synthesize("research",
                List.of(successWithComplete("t1")));

        assertTrue(result.isPresent());
        SkillSynthesisCandidate candidate = result.get();
        assertFalse(candidate.llmRounds() > candidate.baselineLlmRounds(),
                "候选语义判定轮次不得高于基线（只降本）");
    }
}
