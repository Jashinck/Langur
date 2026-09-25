package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 验收 - PlanAndExecute 引擎：多步拆解按序执行、进度事件序列完整、失败触发重规划/降级、
 * 终止闸门对总轮次/Token 生效（子步计量上卷主任务）。
 */
class PlanAndExecuteExecutionLoopTest {

    @Test
    void shouldExecuteAllStepsInOrderAndComplete() {
        StubPlanner planner = new StubPlanner(List.of("收集数据", "分析", "生成报告"), List.of());
        StubStepExecutor executor = new StubStepExecutor(Set.of(), 10L);
        RecordingProgress progress = new RecordingProgress();
        Plan plan = new Plan("a");
        Agent agent = agent("首先 收集数据 然后 分析");

        ExecutionTask task = run(planner, executor, progress, agent, plan, TerminationGate.defaults());

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(3, plan.getSteps().size());
        assertTrue(plan.getSteps().stream().allMatch(s -> s.getStatus() == StepStatus.COMPLETED));
        assertEquals(List.of("收集数据", "分析", "生成报告"), executor.subGoals);
        // 每步一个主任务轮次
        assertEquals(3, task.getCurrentRound());
        assertEquals(30L, task.getConsumedTokens());
    }

    @Test
    void shouldEmitCompleteProgressEventSequence() {
        StubPlanner planner = new StubPlanner(List.of("step-a", "step-b"), List.of());
        StubStepExecutor executor = new StubStepExecutor(Set.of(), 1L);
        RecordingProgress progress = new RecordingProgress();
        Agent agent = agent("do a then b");

        run(planner, executor, progress, agent, new Plan("a"), TerminationGate.defaults());

        assertEquals("plan", progress.events.get(0));
        assertTrue(progress.events.contains("progress"));
        assertEquals("progress", progress.events.get(progress.events.size() - 1));
        assertTrue(progress.data.stream().anyMatch(d -> d.contains("plan finished: COMPLETED")));
        // started + completed per step = 4 progress data lines
        assertEquals(2, progress.data.stream().filter(d -> d.contains("started")).count());
        assertEquals(2, progress.data.stream().filter(d -> d.contains("completed")).count());
    }

    @Test
    void shouldReplanOnStepFailureThenComplete() {
        StubPlanner planner = new StubPlanner(List.of("脆弱步骤", "后续步骤"), List.of("修订步骤"));
        // call#0 失败，其后（含重规划步骤）成功
        StubStepExecutor executor = new StubStepExecutor(Set.of(0), 5L);
        RecordingProgress progress = new RecordingProgress();
        Plan plan = new Plan("a");
        Agent agent = agent("goal");

        ExecutionTask task = run(planner, executor, progress, agent, plan, TerminationGate.defaults());

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(1, planner.replanCalls);
        assertTrue(progress.events.contains("replan"));
        // 脆弱步骤(FAILED) + 修订步骤 + 后续步骤
        assertEquals(3, plan.getSteps().size());
        assertEquals(StepStatus.FAILED, plan.getSteps().get(0).getStatus());
        assertTrue(plan.getSteps().stream().skip(1).allMatch(s -> s.getStatus() == StepStatus.COMPLETED));
    }

    @Test
    void shouldDegradeAndTerminateWhenAllStepsFail() {
        StubPlanner planner = new StubPlanner(List.of("坏步骤"), List.of("仍坏步骤"));
        // 全部失败
        StubStepExecutor executor = new StubStepExecutor(Set.of(0, 1), 1L);
        RecordingProgress progress = new RecordingProgress();
        Agent agent = agent("goal");

        ExecutionTask task = run(planner, executor, progress, agent, new Plan("a"), TerminationGate.defaults());

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("All plan steps failed", task.getTerminateReason());
        assertEquals(1, planner.replanCalls);
        assertTrue(progress.events.contains("replan"));
    }

    @Test
    void shouldSkipRemainingStepsWhenReplansExhausted() {
        StubPlanner planner = new StubPlanner(List.of("失败步骤", "剩余A", "剩余B"), List.of());
        StubStepExecutor executor = new StubStepExecutor(Set.of(0), 1L);
        RecordingProgress progress = new RecordingProgress();
        PlanAndExecuteExecutionLoop loop = loop(planner, executor, progress, TerminationGate.defaults());
        loop.setMaxReplans(0);
        Agent agent = agent("goal");
        Plan plan = new Plan("a");

        ExecutionTask task = loop.execute(
                ExecutionTask.create(agent.getId().getValue(), "default", RuntimeParadigm.PLAN_AND_EXECUTE,
                        TerminationGate.defaults()),
                agent, plan);

        // maxReplans=0：首步失败后直接降级跳过剩余，无成功步骤 → 终止
        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals(1, plan.getSteps().size());
        assertTrue(progress.events.contains("degrade"));
    }

    @Test
    void shouldEnforceTokenGateAcrossSteps() {
        StubPlanner planner = new StubPlanner(List.of("s1", "s2", "s3"), List.of());
        // 每步上卷 1000 token，主闸门 maxTokens=500 → 第二步前触发
        StubStepExecutor executor = new StubStepExecutor(Set.of(), 1000L);
        TerminationGate gate = TerminationGate.builder()
                .maxRounds(10).maxTokens(500L)
                .maxTimeout(java.time.Duration.ofMinutes(5)).maxCallsPerRound(5).build();
        Agent agent = agent("goal");

        ExecutionTask task = run(planner, executor, new RecordingProgress(), agent, new Plan("a"), gate);

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Termination gate tripped", task.getTerminateReason());
        assertEquals(1, executor.subGoals.size(), "闸门触发后不应继续执行后续步骤");
    }

    @Test
    void shouldUseHeuristicPlannerWhenNoExplicitSteps() {
        HeuristicPlanner planner = new HeuristicPlanner();
        StubStepExecutor executor = new StubStepExecutor(Set.of(), 1L);
        Agent agent = agent("首先 采集; 然后 清洗; 最后 汇总");

        ExecutionTask task = run(planner, executor, new RecordingProgress(), agent, new Plan("a"),
                TerminationGate.defaults());

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(3, executor.subGoals.size());
    }

    private PlanAndExecuteExecutionLoop loop(Planner planner, ExecutionLoopService executor,
                                             ExecutionProgressPort progress, TerminationGate gate) {
        PlanAndExecuteExecutionLoop loop = new PlanAndExecuteExecutionLoop(
                executor, planner, new MapTaskStateRepository(), new RecordingEvaluation(), new LifecycleHookEngine());
        loop.attachProgressPort(progress);
        return loop;
    }

    private ExecutionTask run(Planner planner, ExecutionLoopService executor, ExecutionProgressPort progress,
                              Agent agent, Plan plan, TerminationGate gate) {
        PlanAndExecuteExecutionLoop loop = loop(planner, executor, progress, gate);
        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.PLAN_AND_EXECUTE, gate);
        return loop.execute(task, agent, plan);
    }

    private Agent agent(String goal) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("PlanAgent"));
        agent.markRunning();
        agent.addUserMessage(goal);
        return agent;
    }

    /** 步骤执行器桩：按调用序号决定成功/失败，成功时写入 assistant 消息并上卷 token。 */
    private static class StubStepExecutor implements ExecutionLoopService {
        final List<String> subGoals = new ArrayList<>();
        private final Set<Integer> failingCalls;
        private final long tokensPerStep;
        private int calls = 0;

        StubStepExecutor(Set<Integer> failingCalls, long tokensPerStep) {
            this.failingCalls = new HashSet<>(failingCalls);
            this.tokensPerStep = tokensPerStep;
        }

        @Override
        public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
            int idx = calls++;
            task.start();
            task.nextRound();
            task.addTokens(tokensPerStep);
            subGoals.add(lastUser(agent));
            if (failingCalls.contains(idx)) {
                task.terminate("stub failure #" + idx);
            } else {
                agent.addAssistantMessage("answer-" + idx);
                task.complete();
            }
            return task;
        }

        private String lastUser(Agent agent) {
            List<Map<String, String>> history = agent.getConversationHistory();
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    String content = history.get(i).get("content");
                    return content.startsWith("[子目标] ") ? content.substring("[子目标] ".length()) : content;
                }
            }
            return "";
        }
    }

    /** 规划器桩：首次规划与重规划返回预置步骤。 */
    private static class StubPlanner implements Planner {
        private final List<String> firstPlan;
        private final List<String> replanSteps;
        int planCalls = 0;
        int replanCalls = 0;

        StubPlanner(List<String> firstPlan, List<String> replanSteps) {
            this.firstPlan = firstPlan;
            this.replanSteps = replanSteps;
        }

        @Override
        public List<PlanStep> plan(String goal, Agent agent) {
            planCalls++;
            return toSteps(firstPlan);
        }

        @Override
        public List<PlanStep> replan(String goal, Agent agent, List<PlanStep> executed,
                                     PlanStep failedStep, String failureReason) {
            replanCalls++;
            return toSteps(replanSteps);
        }

        private List<PlanStep> toSteps(List<String> thoughts) {
            List<PlanStep> steps = new ArrayList<>();
            for (int i = 0; i < thoughts.size(); i++) {
                steps.add(PlanStep.builder().index(i).thought(thoughts.get(i))
                        .action("react").status(StepStatus.PENDING).build());
            }
            return steps;
        }
    }

    private static class RecordingProgress implements ExecutionProgressPort {
        final List<String> events = new ArrayList<>();
        final List<String> data = new ArrayList<>();

        @Override
        public void publish(String taskId, String event, String payload) {
            events.add(event);
            data.add(payload);
        }
    }

    private static class RecordingEvaluation implements EvaluationService {
        final List<ExecutionMetrics> metrics = new ArrayList<>();

        @Override
        public void report(ExecutionMetrics executionMetrics) {
            metrics.add(executionMetrics);
        }

        @Override
        public void audit(AuditRecord record) {
        }
    }

    private static class MapTaskStateRepository implements TaskStateRepository {
        private final Map<String, TaskState> store = new HashMap<>();

        @Override
        public void save(TaskState state) {
            store.put(state.getTaskId(), state);
        }

        @Override
        public Optional<TaskState> findById(String taskId) {
            return Optional.ofNullable(store.get(taskId));
        }
    }
}
