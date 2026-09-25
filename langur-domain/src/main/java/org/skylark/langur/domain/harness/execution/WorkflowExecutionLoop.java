package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.lifecycle.HookAction;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.security.ApprovalStatus;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * E 组件顶层实现 - Workflow 执行引擎（H9，§5.3）。
 * <p>Harness 全权驱动：阶段顺序由 {@link WorkflowDefinition} 固化，LLM 不参与控制流决策。标准时序：
 * 解析工作流定义 → 逐阶段执行（审批闸门阶段先经 {@link ApprovalPort} 校验，未批准则挂起）→
 * 每阶段委派下层执行器 {@code stageExecutor}（WORKFLOW→ReAct，HYBRID→PlanAndExecute，后者再委派 ReAct）→
 * [S] 每阶段快照（记录已完成阶段，支撑挂起-审批-恢复续跑）→ [V] 进度回报 → [L] 钩子拦截。</p>
 * <p>与中层 PlanAndExecute 不同：阶段失败即中断（无动态重规划），体现强合规/审批的确定性边界。
 * 子阶段 Token 上卷主任务，{@link ExecutionTask#gateTripped()} 对总轮次/Token 全局生效。
 * 审批以 {@code taskId} 为关联键（跨 resume 稳定，避免续跑换 traceId 后重复挂起）。
 * 纯领域实现（零 Spring 依赖，P1），由 start 层装配。</p>
 */
public class WorkflowExecutionLoop implements ExecutionLoopService {

    private final ExecutionLoopService stageExecutor;
    private final RuntimeParadigm stageParadigm;
    private final WorkflowRepository workflowRepository;
    private final ApprovalPort approvalPort;
    private final TaskStateRepository taskStateRepository;
    private final EvaluationService evaluationService;
    private final LifecycleHookEngine hookEngine;

    /** 进度回报端口（可选装配，§13.3）；缺省 NOOP（P10）。 */
    private ExecutionProgressPort progressPort = ExecutionProgressPort.NOOP;

    public WorkflowExecutionLoop(ExecutionLoopService stageExecutor,
                                 RuntimeParadigm stageParadigm,
                                 WorkflowRepository workflowRepository,
                                 ApprovalPort approvalPort,
                                 TaskStateRepository taskStateRepository,
                                 EvaluationService evaluationService,
                                 LifecycleHookEngine hookEngine) {
        this.stageExecutor = stageExecutor;
        this.stageParadigm = stageParadigm != null ? stageParadigm : RuntimeParadigm.REACT;
        this.workflowRepository = workflowRepository;
        this.approvalPort = approvalPort;
        this.taskStateRepository = taskStateRepository;
        this.evaluationService = evaluationService;
        this.hookEngine = hookEngine;
    }

    public void attachProgressPort(ExecutionProgressPort progressPort) {
        if (progressPort != null) {
            this.progressPort = progressPort;
        }
    }

    @Override
    public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
        long startMillis = System.currentTimeMillis();
        task.start();

        String lockHolder = task.getTraceId();
        TaskState state = acquireState(task, lockHolder);
        if (state == null) {
            task.terminate("Concurrent execution rejected: task is locked by another holder");
            return task;
        }
        state.transition(TaskStateStatus.RUNNING);

        String goal = lastUserMessage(agent);
        WorkflowDefinition definition = resolveDefinition(task.getBizCode(), goal);
        List<WorkflowStage> stages = definition.getStages();
        Set<String> completed = recoverCompleted(state);
        List<String> observations = new ArrayList<>();
        int stageTotal = stages.size();
        int approvals = 0;
        boolean gateTripped = false;
        boolean suspended = false;
        String terminateReason = null;

        publish(task, "workflow", "start [" + definition.getName() + "] stages=" + stageTotal
                + " layer=WORKFLOW stageExecutor=" + stageParadigm.name());

        for (WorkflowStage stage : stages) {
            if (task.gateTripped()) {
                gateTripped = true;
                break;
            }
            if (completed.contains(stage.getId())) {
                publish(task, "progress", "stage " + stage.getId() + " already completed, skip");
                continue;
            }
            task.nextRound();
            int stepIndex = plan.getSteps().size();
            PlanStep running = PlanStep.builder()
                    .index(stepIndex)
                    .thought(stage.getInstruction())
                    .action("workflow:" + stage.getId())
                    .status(StepStatus.RUNNING)
                    .build();
            plan.addStep(running);
            publish(task, "progress", "stage " + stage.getId() + " started: " + abbreviate(stage.getInstruction()));

            // 审批闸门（强合规边界）
            if (stage.isRequiresApproval()) {
                if (approvalPort == null) {
                    // fail-closed：需审批却无审批后端 → 中断，不放行
                    plan.updateStep(stepIndex, running.withFailure("no approval backend"));
                    terminateReason = "Workflow stage requires approval but no approval backend configured: "
                            + stage.getId();
                    publish(task, "approval", terminateReason);
                    snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
                    break;
                }
                approvals++;
                ApprovalGate gate = checkApproval(task, stage);
                if (gate.decision() == Decision.DENIED) {
                    plan.updateStep(stepIndex, running.withFailure("approval denied"));
                    terminateReason = "Workflow approval denied: " + stage.getId() + " [" + gate.requestId() + "]";
                    publish(task, "approval", terminateReason);
                    snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
                    break;
                }
                if (gate.decision() == Decision.PENDING) {
                    plan.updateStep(stepIndex, running.withFailure("awaiting approval"));
                    suspended = true;
                    terminateReason = "Workflow stage awaiting approval: " + stage.getId() + " [" + gate.requestId() + "]";
                    publish(task, "approval", terminateReason);
                    snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
                    break;
                }
                publish(task, "approval", "stage " + stage.getId() + " approved, proceed");
            }

            StageOutcome outcome = runStage(task, agent, stage);
            task.addTokens(outcome.tokens);
            if (outcome.success) {
                completed.add(stage.getId());
                if (outcome.answer != null) {
                    observations.add(outcome.answer);
                }
                if (stage.producesArtifact()) {
                    task.addArtifact(stage.getArtifactName(), stage.getArtifactType(), outcome.answer);
                }
                plan.updateStep(stepIndex, running.withObservation(outcome.answer));
                publish(task, "progress", "stage " + stage.getId() + " completed");
            } else {
                plan.updateStep(stepIndex, running.withFailure(outcome.reason));
                publish(task, "progress", "stage " + stage.getId() + " failed: " + outcome.reason);
                terminateReason = "Workflow stage failed: " + stage.getId() + " - " + outcome.reason;
                snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
                break;
            }
            snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
        }

        finalizeTask(task, agent, state, plan, observations, gateTripped, suspended, terminateReason);

        fire(HookPoint.BEFORE_TERMINATE, task, task.getTerminateReason());
        fire(HookPoint.AFTER_TERMINATE, task, String.valueOf(task.getStatus()));

        state.releaseLock();
        taskStateRepository.save(state);

        publish(task, "progress", "workflow finished: " + task.getStatus()
                + " stages=" + completed.size() + "/" + stageTotal + " approvals=" + approvals);
        reportMetrics(task, startMillis, stageTotal, completed.size(), approvals, gateTripped, suspended);
        return task;
    }

    /** 委派下层执行器执行单个阶段：以阶段指令框定为子目标，子任务独立计量/状态。 */
    private StageOutcome runStage(ExecutionTask master, Agent agent, WorkflowStage stage) {
        agent.addUserMessage("[工作流阶段 " + stage.getId() + "] " + stage.getInstruction());
        ExecutionTask subTask = ExecutionTask.create(
                master.getAgentId(), master.getBizCode(), stageParadigm, master.getGate());
        Plan subPlan = new Plan(master.getAgentId());
        try {
            stageExecutor.execute(subTask, agent, subPlan);
        } catch (RuntimeException e) {
            return StageOutcome.failure(subTask.getConsumedTokens(), "stage executor error: " + e.getMessage());
        }
        if (subTask.getStatus() == ExecutionStatus.COMPLETED) {
            return StageOutcome.success(subTask.getConsumedTokens(), lastAssistantMessage(agent));
        }
        String reason = subTask.getTerminateReason() != null
                ? subTask.getTerminateReason()
                : "stage not completed: " + subTask.getStatus();
        return StageOutcome.failure(subTask.getConsumedTokens(), reason);
    }

    /** 审批闸门校验：以 taskId 为关联键（跨 resume 稳定）；无单则创建 PENDING 并挂起。 */
    private ApprovalGate checkApproval(ExecutionTask task, WorkflowStage stage) {
        String approvalToolId = "workflow:" + stage.getId();
        Optional<ApprovalRequest> existing = approvalPort.findLatest(task.getTaskId(), approvalToolId);
        if (existing.isEmpty()) {
            ApprovalRequest request = ApprovalRequest.of(UUID.randomUUID().toString(), task.getTaskId(),
                    "workflow:" + task.getBizCode(), approvalToolId,
                    "Workflow stage approval: " + stage.getId());
            approvalPort.save(request);
            return new ApprovalGate(Decision.PENDING, request.getRequestId());
        }
        ApprovalRequest request = existing.get();
        ApprovalStatus status = request.getStatus();
        if (status == ApprovalStatus.APPROVED) {
            return new ApprovalGate(Decision.APPROVED, request.getRequestId());
        }
        if (status == ApprovalStatus.DENIED) {
            return new ApprovalGate(Decision.DENIED, request.getRequestId());
        }
        return new ApprovalGate(Decision.PENDING, request.getRequestId());
    }

    private void finalizeTask(ExecutionTask task, Agent agent, TaskState state, Plan plan,
                              List<String> observations, boolean gateTripped, boolean suspended,
                              String terminateReason) {
        if (suspended) {
            task.terminate(terminateReason);
            state.transition(TaskStateStatus.SUSPENDED);
            return;
        }
        if (gateTripped) {
            task.terminate("Termination gate tripped");
            state.transition(TaskStateStatus.FAILED);
            return;
        }
        if (terminateReason != null) {
            task.terminate(terminateReason);
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

    private WorkflowDefinition resolveDefinition(String bizCode, String goal) {
        if (workflowRepository != null) {
            Optional<WorkflowDefinition> found = workflowRepository.findByBizCode(bizCode);
            if (found.isPresent() && found.get().getStages() != null && !found.get().getStages().isEmpty()) {
                return found.get();
            }
        }
        String instruction = goal == null || goal.isBlank() ? "处理用户请求" : goal.trim();
        WorkflowStage stage = isComplianceBizCode(bizCode)
                ? WorkflowStage.approval("main", instruction)
                : WorkflowStage.of("main", instruction);
        return WorkflowDefinition.of(bizCode, "fallback-" + bizCode, List.of(stage));
    }

    private boolean isComplianceBizCode(String bizCode) {
        if (bizCode == null) {
            return false;
        }
        String code = bizCode.toLowerCase();
        return code.contains("compliance") || code.contains("approval");
    }

    @SuppressWarnings("unchecked")
    private Set<String> recoverCompleted(TaskState state) {
        Set<String> completed = new LinkedHashSet<>();
        StateSnapshot latest = state.latestSnapshot();
        if (latest != null && latest.getPayload() != null) {
            Object value = latest.getPayload().get("completedStages");
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    completed.add(String.valueOf(item));
                }
            }
        }
        return completed;
    }

    private void snapshotStage(ExecutionTask task, TaskState state, Agent agent, Plan plan,
                               Set<String> completed, int stageTotal, int approvals) {
        if (fire(HookPoint.BEFORE_STATE_SAVE, task, null) != null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentStatus", agent.getStatus().name());
        payload.put("workflowStages", stageTotal);
        payload.put("completedStages", new ArrayList<>(completed));
        payload.put("approvals", approvals);
        state.addSnapshot(StateSnapshot.of(task.getTaskId(), task.getCurrentRound(), payload));
        taskStateRepository.save(state);
        fire(HookPoint.AFTER_STATE_SAVE, task, state.latestSnapshot().getSnapshotId());
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

    private String aggregate(List<String> observations, Plan plan) {
        if (observations.size() == 1) {
            return observations.get(0);
        }
        if (!observations.isEmpty()) {
            return String.join("\n", observations);
        }
        return plan.toTraceString();
    }

    private void publish(ExecutionTask task, String event, String data) {
        try {
            progressPort.publish(task.getTaskId(), event, data);
        } catch (RuntimeException ignored) {
            // P10：进度上报失败静默丢弃
        }
    }

    private HookResult fire(HookPoint point, ExecutionTask task, String payload) {
        if (hookEngine == null) {
            return null;
        }
        HookResult result = hookEngine.fire(HookContext.of(point, task.getTraceId(), payload));
        return result != null && result.getAction() == HookAction.ABORT ? result : null;
    }

    private void reportMetrics(ExecutionTask task, long startMillis, int stageTotal,
                               int completedStages, int approvals, boolean gateTripped, boolean suspended) {
        if (evaluationService == null) {
            return;
        }
        try {
            ExecutionMetrics metrics = ExecutionMetrics.of(task.getTraceId());
            metrics.record(MetricDimension.SCHEDULING, "paradigm", task.getParadigm().name());
            metrics.record(MetricDimension.SCHEDULING, "layer", layerOf(task).name());
            metrics.record(MetricDimension.SCHEDULING, "stageExecutor", stageParadigm.name());
            metrics.record(MetricDimension.SCHEDULING, "workflowStages", stageTotal);
            metrics.record(MetricDimension.SCHEDULING, "completedStages", completedStages);
            metrics.record(MetricDimension.SCHEDULING, "approvals", approvals);
            metrics.record(MetricDimension.SCHEDULING, "suspended", suspended ? 1 : 0);
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

    /** 指标 layer 标签按主任务范式派生：HYBRID→HYBRID_LAYER，否则 WORKFLOW_LAYER。 */
    private RuntimeLayer layerOf(ExecutionTask task) {
        return task.getParadigm() == RuntimeParadigm.HYBRID
                ? RuntimeLayer.HYBRID_LAYER
                : RuntimeLayer.WORKFLOW_LAYER;
    }

    private enum Decision {
        APPROVED, PENDING, DENIED
    }

    private record ApprovalGate(Decision decision, String requestId) {
    }

    /** 单阶段执行结果（内部值对象）。 */
    private static final class StageOutcome {
        private final boolean success;
        private final long tokens;
        private final String answer;
        private final String reason;

        private StageOutcome(boolean success, long tokens, String answer, String reason) {
            this.success = success;
            this.tokens = tokens;
            this.answer = answer;
            this.reason = reason;
        }

        static StageOutcome success(long tokens, String answer) {
            return new StageOutcome(true, tokens, answer, null);
        }

        static StageOutcome failure(long tokens, String reason) {
            return new StageOutcome(false, tokens, null, reason);
        }
    }
}
