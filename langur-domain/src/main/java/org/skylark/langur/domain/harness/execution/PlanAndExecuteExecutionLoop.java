package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.lifecycle.HookAction;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E 组件中层实现 - PlanAndExecute 执行循环（H3，§5.3）。
 * <p>标准时序：[E] M5 规划拆解 → 逐步执行（每步委派底层 ReAct 子循环 {@code stepExecutor}）→
 * [S] 每步快照 → [V] 进度回报（{@link ExecutionProgressPort}）→ 步骤失败触发动态重规划（{@link Planner#replan}）
 * 或降级跳过。复用底层循环的终止闸门/钩子/循环检测/度量：子步 Token 与轮次上卷至主任务，
 * 主任务 {@link ExecutionTask#gateTripped()} 对总轮次/Token 硬约束生效（§5.3 全局兜底）。</p>
 * <p>纯领域实现（零 Spring 依赖，P1），由 start 层装配。</p>
 */
public class PlanAndExecuteExecutionLoop implements ExecutionLoopService {

    private final ExecutionLoopService stepExecutor;
    private final Planner planner;
    private final TaskStateRepository taskStateRepository;
    private final EvaluationService evaluationService;
    private final LifecycleHookEngine hookEngine;

    /** 进度回报端口（可选装配，§13.3）；缺省 NOOP，不产生进度事件（P10）。 */
    private ExecutionProgressPort progressPort = ExecutionProgressPort.NOOP;

    /** 单个任务允许的最大重规划次数，超出后降级跳过剩余步骤。 */
    private int maxReplans = 1;

    public PlanAndExecuteExecutionLoop(ExecutionLoopService stepExecutor,
                                       Planner planner,
                                       TaskStateRepository taskStateRepository,
                                       EvaluationService evaluationService,
                                       LifecycleHookEngine hookEngine) {
        this.stepExecutor = stepExecutor;
        this.planner = planner != null ? planner : new HeuristicPlanner();
        this.taskStateRepository = taskStateRepository;
        this.evaluationService = evaluationService;
        this.hookEngine = hookEngine;
    }

    public void attachProgressPort(ExecutionProgressPort progressPort) {
        if (progressPort != null) {
            this.progressPort = progressPort;
        }
    }

    public void setMaxReplans(int maxReplans) {
        this.maxReplans = Math.max(0, maxReplans);
    }

    @Override
    public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
        long startMillis = System.currentTimeMillis();
        task.start();

        // [S] 主任务并发重入保护（与底层循环一致，复用 TaskState 锁语义）
        String lockHolder = task.getTraceId();
        TaskState state = acquireState(task, lockHolder);
        if (state == null) {
            task.terminate("Concurrent execution rejected: task is locked by another holder");
            return task;
        }
        state.transition(TaskStateStatus.RUNNING);

        String goal = lastUserMessage(agent);
        publish(task, "plan", "planning: " + abbreviate(goal));
        List<PlanStep> initial = safePlan(goal, agent);

        Deque<PlanStep> queue = new ArrayDeque<>(initial);
        List<String> observations = new ArrayList<>();
        int planned = initial.size();
        int completedSteps = 0;
        int replans = 0;
        boolean gateTripped = false;

        while (!queue.isEmpty()) {
            // [E] 主任务闸门：总轮次/Token/超时任一超限即中断编排（全局兜底）
            if (task.gateTripped()) {
                gateTripped = true;
                break;
            }
            PlanStep step = queue.poll();
            int stepIndex = plan.getSteps().size();
            PlanStep running = PlanStep.builder()
                    .index(stepIndex)
                    .thought(step.getThought())
                    .action(step.getAction())
                    .actionInput(step.getActionInput())
                    .status(StepStatus.RUNNING)
                    .build();
            plan.addStep(running);
            task.nextRound();
            publish(task, "progress", "step " + (stepIndex + 1) + " started: " + abbreviate(step.getThought()));

            StepOutcome outcome = executeStep(task, agent, step);
            // [E] 子步计量上卷至主任务，使主闸门对总消耗生效
            task.addTokens(outcome.tokens);

            if (outcome.success) {
                completedSteps++;
                if (outcome.answer != null) {
                    observations.add(outcome.answer);
                }
                plan.updateStep(stepIndex, running.withObservation(outcome.answer));
                publish(task, "progress", "step " + (stepIndex + 1) + " completed");
            } else {
                plan.updateStep(stepIndex, running.withFailure(outcome.reason));
                publish(task, "progress", "step " + (stepIndex + 1) + " failed: " + outcome.reason);
                if (replans < maxReplans) {
                    replans++;
                    publish(task, "replan", "replanning after failure: " + outcome.reason);
                    List<PlanStep> revised = safeReplan(goal, agent, plan.getSteps(), running, outcome.reason);
                    // 重规划步骤前插，优先于原剩余步骤执行
                    for (int i = revised.size() - 1; i >= 0; i--) {
                        queue.addFirst(revised.get(i));
                    }
                    planned += revised.size();
                } else if (!queue.isEmpty()) {
                    publish(task, "degrade", "max replans reached, skip remaining " + queue.size() + " steps");
                    queue.clear();
                }
            }

            // [S] 每步快照写入（断点续跑/回滚基础）
            if (fire(HookPoint.BEFORE_STATE_SAVE, task, null) == null) {
                state.addSnapshot(StateSnapshot.of(task.getTaskId(), task.getCurrentRound(),
                        snapshotPayload(agent, plan, completedSteps, replans)));
                taskStateRepository.save(state);
                fire(HookPoint.AFTER_STATE_SAVE, task, state.latestSnapshot().getSnapshotId());
            }
        }

        finalizeTask(task, agent, state, plan, observations, gateTripped, completedSteps);

        // [L] 终止阶段拦截（与底层循环一致）
        fire(HookPoint.BEFORE_TERMINATE, task, task.getTerminateReason());
        fire(HookPoint.AFTER_TERMINATE, task, String.valueOf(task.getStatus()));

        state.releaseLock();
        taskStateRepository.save(state);

        publish(task, "progress", "plan finished: " + task.getStatus()
                + " steps=" + completedSteps + "/" + planned + " replans=" + replans);
        reportMetrics(task, startMillis, planned, completedSteps, replans, gateTripped);
        return task;
    }

    /**
     * 委派底层 ReAct 子循环执行单个高层步骤：以子目标框定为 user 消息，子任务独立计量/状态。
     */
    private StepOutcome executeStep(ExecutionTask master, Agent agent, PlanStep step) {
        agent.addUserMessage("[子目标] " + step.getThought());
        ExecutionTask subTask = ExecutionTask.create(
                master.getAgentId(), master.getBizCode(), RuntimeParadigm.REACT, master.getGate());
        Plan subPlan = new Plan(master.getAgentId());
        try {
            stepExecutor.execute(subTask, agent, subPlan);
        } catch (RuntimeException e) {
            return StepOutcome.failure(subTask.getConsumedTokens(), "step executor error: " + e.getMessage());
        }
        if (subTask.getStatus() == ExecutionStatus.COMPLETED) {
            return StepOutcome.success(subTask.getConsumedTokens(), lastAssistantMessage(agent));
        }
        String reason = subTask.getTerminateReason() != null
                ? subTask.getTerminateReason()
                : "step not completed: " + subTask.getStatus();
        return StepOutcome.failure(subTask.getConsumedTokens(), reason);
    }

    private void finalizeTask(ExecutionTask task, Agent agent, TaskState state, Plan plan,
                              List<String> observations, boolean gateTripped, int completedSteps) {
        if (gateTripped) {
            task.terminate("Termination gate tripped");
            state.transition(TaskStateStatus.FAILED);
            return;
        }
        if (completedSteps == 0) {
            task.terminate("All plan steps failed");
            state.transition(TaskStateStatus.FAILED);
            return;
        }
        String aggregate = aggregate(observations, plan);
        if (agent.getStatus() != AgentStatus.COMPLETED) {
            agent.markCompleted(aggregate);
        }
        task.complete();
        state.transition(TaskStateStatus.COMPLETED);
        fire(HookPoint.AFTER_OUTPUT, task, aggregate);
    }

    private String aggregate(List<String> observations, Plan plan) {
        if (observations.size() == 1) {
            return observations.get(0);
        }
        if (!observations.isEmpty()) {
            return String.join("\n", observations);
        }
        return plan.toTraceString();
    }

    private TaskState acquireState(ExecutionTask task, String lockHolder) {
        TaskState state = taskStateRepository.findById(task.getTaskId()).orElse(null);
        if (state != null) {
            return state.acquireLock(lockHolder) ? state : null;
        }
        state = TaskState.init(task.getTaskId());
        state.acquireLock(lockHolder);
        return state;
    }

    private List<PlanStep> safePlan(String goal, Agent agent) {
        try {
            List<PlanStep> steps = planner.plan(goal, agent);
            return steps != null && !steps.isEmpty() ? steps : fallbackSteps(goal);
        } catch (RuntimeException e) {
            return fallbackSteps(goal);
        }
    }

    private List<PlanStep> safeReplan(String goal, Agent agent, List<PlanStep> executed,
                                      PlanStep failedStep, String reason) {
        try {
            List<PlanStep> steps = planner.replan(goal, agent, executed, failedStep, reason);
            return steps != null ? steps : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<PlanStep> fallbackSteps(String goal) {
        String text = goal == null || goal.isBlank() ? "处理用户请求" : goal.trim();
        return List.of(PlanStep.builder().index(0).thought(text).action("react").status(StepStatus.PENDING).build());
    }

    private void publish(ExecutionTask task, String event, String data) {
        try {
            progressPort.publish(task.getTaskId(), event, data);
        } catch (RuntimeException ignored) {
            // P10：进度上报失败静默丢弃，不影响主链路
        }
    }

    private HookResult fire(HookPoint point, ExecutionTask task, String payload) {
        if (hookEngine == null) {
            return null;
        }
        HookResult result = hookEngine.fire(HookContext.of(point, task.getTraceId(), payload));
        return result != null && result.getAction() == HookAction.ABORT ? result : null;
    }

    private Map<String, Object> snapshotPayload(Agent agent, Plan plan, int completedSteps, int replans) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentStatus", agent.getStatus().name());
        payload.put("planSteps", plan.getSteps().size());
        payload.put("completedSteps", completedSteps);
        payload.put("replans", replans);
        return payload;
    }

    private void reportMetrics(ExecutionTask task, long startMillis, int planned,
                               int completedSteps, int replans, boolean gateTripped) {
        if (evaluationService == null) {
            return;
        }
        try {
            ExecutionMetrics metrics = ExecutionMetrics.of(task.getTraceId());
            metrics.record(MetricDimension.SCHEDULING, "paradigm", task.getParadigm().name());
            metrics.record(MetricDimension.SCHEDULING, "totalRounds", task.getCurrentRound());
            metrics.record(MetricDimension.SCHEDULING, "plannedSteps", planned);
            metrics.record(MetricDimension.SCHEDULING, "completedSteps", completedSteps);
            metrics.record(MetricDimension.SCHEDULING, "replans", replans);
            metrics.record(MetricDimension.SCHEDULING, "elapsedMillis", System.currentTimeMillis() - startMillis);
            metrics.record(MetricDimension.SCHEDULING, "terminateReason",
                    task.getTerminateReason() != null ? task.getTerminateReason() : "COMPLETED");
            metrics.record(MetricDimension.MODEL, "consumedTokens", task.getConsumedTokens());
            if (gateTripped) {
                metrics.record(MetricDimension.SECURITY, "interceptions", 1);
            }
            evaluationService.report(metrics);
        } catch (RuntimeException ignored) {
            // P10：埋点失败静默丢弃
        }
    }

    private String lastUserMessage(Agent agent) {
        return lastMessageByRole(agent, "user");
    }

    private String lastAssistantMessage(Agent agent) {
        return lastMessageByRole(agent, "assistant");
    }

    private String lastMessageByRole(Agent agent, String role) {
        List<Map<String, String>> history = agent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> message = history.get(i);
            if (role.equals(message.get("role"))) {
                return message.get("content");
            }
        }
        return "";
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    /** 单步执行结果（内部值对象）。 */
    private static final class StepOutcome {
        private final boolean success;
        private final long tokens;
        private final String answer;
        private final String reason;

        private StepOutcome(boolean success, long tokens, String answer, String reason) {
            this.success = success;
            this.tokens = tokens;
            this.answer = answer;
            this.reason = reason;
        }

        static StepOutcome success(long tokens, String answer) {
            return new StepOutcome(true, tokens, answer, null);
        }

        static StepOutcome failure(long tokens, String reason) {
            return new StepOutcome(false, tokens, null, reason);
        }
    }
}
