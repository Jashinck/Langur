package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
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
import org.skylark.langur.domain.harness.workflow.GateRoute;
import org.skylark.langur.domain.harness.workflow.StageDecisionGate;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.domain.port.DecisionPort;

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

    /** J6：产物验收不达标时的有界重试上限（受终止闸门双重约束，绝不无限重试）。 */
    private static final int MAX_ARTIFACT_RETRIES = 1;

    private final ExecutionLoopService stageExecutor;
    private final RuntimeParadigm stageParadigm;
    private final WorkflowRepository workflowRepository;
    private final ApprovalPort approvalPort;
    private final TaskStateRepository taskStateRepository;
    private final EvaluationService evaluationService;
    private final LifecycleHookEngine hookEngine;

    /** 进度回报端口（可选装配，§13.3）；缺省 NOOP（P10）。 */
    private ExecutionProgressPort progressPort = ExecutionProgressPort.NOOP;

    /**
     * 决策平面端口（J4–J6，可选装配，v3.0 §3.1）。缺省 {@code null} → 全部插入点走规则/默认顺序兜底，
     * 行为与 v2.0 完全一致（P10/P12①）。装配时为 J3 装饰链（录制 ⊃ 缓存 ⊃ 阈值 ⊃ 后端）。
     */
    private DecisionPort decisionPort;

    /** 各插入点置信阈值（J5 审批自动放行 / J6 产物验收）；缺省 DD11 经验值。 */
    private DecisionThresholds decisionThresholds = DecisionThresholds.defaults();

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

    /**
     * 装配决策平面（J4–J6，可选）：注入 J3 装饰链端口 + 各插入点置信阈值。
     * <p>{@code null} 端口 → 保持缺省，全部插入点走规则/默认顺序兜底（P10/P12①）；
     * {@code null} 阈值 → 沿用 DD11 缺省（approval-auto 0.90 / artifact-accept 0.80）。</p>
     */
    public void attachDecisionPlane(DecisionPort decisionPort, DecisionThresholds thresholds) {
        this.decisionPort = decisionPort;
        if (thresholds != null) {
            this.decisionThresholds = thresholds;
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

        int branchBudget = stages.size(); // J4：分支跳转防环预算（跳转次数不超过阶段总数）
        int index = 0;
        while (index < stages.size()) {
            WorkflowStage stage = stages.get(index);
            if (task.gateTripped()) {
                gateTripped = true;
                break;
            }
            if (completed.contains(stage.getId())) {
                publish(task, "progress", "stage " + stage.getId() + " already completed, skip");
                index++;
                continue;
            }
            // J4：上一阶段决策闸门若升级过人审（挂起后恢复续跑），DENIED/PENDING 不得越过（只收紧，P12②）
            if (index > 0 && approvalPort != null) {
                GateApprovalBlock block = findBlockingGateApproval(task, stages.get(index - 1).getId());
                if (block != null) {
                    suspended = block.pending();
                    terminateReason = (block.pending()
                            ? "Workflow stage awaiting approval: decision-gate "
                            : "Decision gate approval denied: ")
                            + stages.get(index - 1).getId() + " [" + block.requestId() + "]";
                    publish(task, "approval", terminateReason);
                    snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
                    break;
                }
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

            // 审批闸门（强合规边界）；J5：非 CRITICAL 且决策平面在场 → score 三分流（低风险自动放行快路）
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
                ApprovalGate gate = checkApproval(task, goal, stage);
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
                    // J6：addArtifact 前经决策平面 score 验收（低于阈值 → 有界重试或打标不阻断）
                    task.addArtifact(reviewArtifact(task, agent, stage, outcome));
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

            // J4：阶段产出后经决策闸门判定走向；低置信/缺失 → RUN_NEXT 默认顺序兜底（P10）
            GateRoute route = evaluateStageGate(task, stage, outcome.answer);
            if (route == GateRoute.SKIP && index + 1 < stages.size()) {
                WorkflowStage next = stages.get(index + 1);
                completed.add(next.getId());
                publish(task, "progress", "stage " + next.getId() + " skipped by decision gate (J4)");
                snapshotStage(task, state, agent, plan, completed, stageTotal, approvals);
            } else if (route == GateRoute.BRANCH) {
                String target = stage.getDecisionGate().branchStageId();
                int targetIndex = indexOfStage(stages, target);
                if (targetIndex >= 0 && branchBudget > 0) {
                    branchBudget--;
                    publish(task, "progress", "decision gate branch: " + stage.getId()
                            + " -> " + stages.get(targetIndex).getId());
                    index = targetIndex;
                    continue;
                }
                publish(task, "progress", "decision gate branch ignored (unknown target '"
                        + target + "' or budget exhausted), default order");
            } else if (route == GateRoute.ABORT) {
                terminateReason = "Workflow aborted by decision gate at stage: " + stage.getId();
                publish(task, "progress", terminateReason);
                break;
            } else if (route == GateRoute.REQUIRE_APPROVAL) {
                if (approvalPort == null) {
                    terminateReason = "Decision gate requires approval but no approval backend configured: "
                            + stage.getId();
                    publish(task, "approval", terminateReason);
                    break;
                }
                approvals++;
                String gateToolId = "workflow-gate:" + stage.getId();
                Optional<ApprovalRequest> existing = approvalPort.findLatest(task.getTaskId(), gateToolId);
                if (existing.isEmpty()) {
                    ApprovalRequest request = ApprovalRequest.of(UUID.randomUUID().toString(), task.getTaskId(),
                            "workflow:" + task.getBizCode(), gateToolId,
                            "Decision gate escalation at stage: " + stage.getId());
                    approvalPort.save(request);
                    suspended = true;
                    terminateReason = "Workflow stage awaiting approval: decision-gate " + stage.getId()
                            + " [" + request.getRequestId() + "]";
                } else if (existing.get().getStatus() == ApprovalStatus.DENIED) {
                    terminateReason = "Decision gate approval denied: " + stage.getId()
                            + " [" + existing.get().getRequestId() + "]";
                } else if (existing.get().getStatus() == ApprovalStatus.PENDING) {
                    suspended = true;
                    terminateReason = "Workflow stage awaiting approval: decision-gate " + stage.getId()
                            + " [" + existing.get().getRequestId() + "]";
                } else {
                    publish(task, "approval", "decision gate already approved, proceed");
                }
                if (terminateReason != null) {
                    publish(task, "approval", terminateReason);
                    break;
                }
            }
            index++;
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

    /**
     * 审批闸门校验：以 taskId 为关联键（跨 resume 稳定）；无单则创建 PENDING 并挂起。
     * <p>J5（插入点 ②）：无既有单且 <b>非 CRITICAL</b> 且决策平面在场时，先经 {@code score} 三分流——
     * 安全分与置信均 ≥ {@code approval-auto}（0.90）→ 策略内自动放行快路（留 APPROVED 审批单可溯源）；
     * 中分/高分风险/低置信 → 人审（fail-closed，P12③）。<b>CRITICAL 恒人审</b>：绝不咨询决策平面、
     * 绝不自动放行（P12②只收紧不放松）。</p>
     */
    private ApprovalGate checkApproval(ExecutionTask task, String goal, WorkflowStage stage) {
        String approvalToolId = "workflow:" + stage.getId();
        Optional<ApprovalRequest> existing = approvalPort.findLatest(task.getTaskId(), approvalToolId);
        if (existing.isEmpty()) {
            if (!stage.isCritical() && decisionPort != null) {
                DecisionAnswer answer = decideQuietly(approvalState(stage, goal),
                        DecisionQuestion.score("approval-auto",
                                "评估自动放行该工作流审批阶段的合规安全分（0-1，越安全越高）；仅在高分且高置信时可自动放行"));
                boolean autoPass = answer != null
                        && answer.confidence() >= decisionThresholds.approvalAuto()
                        && answer.value() >= decisionThresholds.approvalAuto();
                emitRouteCount(autoPass ? "approve" : "fail_closed");
                ApprovalRequest request = ApprovalRequest.of(UUID.randomUUID().toString(), task.getTaskId(),
                        "workflow:" + task.getBizCode(), approvalToolId,
                        "Workflow stage approval: " + stage.getId());
                if (autoPass) {
                    request.approve("decision-plane", String.format(
                            "J5 auto-passed non-CRITICAL approval: score=%.3f confidence=%.3f >= %.2f",
                            answer.value(), answer.confidence(), decisionThresholds.approvalAuto()));
                    approvalPort.save(request);
                    return new ApprovalGate(Decision.APPROVED, request.getRequestId());
                }
                // 中/高风险或低置信 → 落 PENDING 人审（不自动放行）
                approvalPort.save(request);
                return new ApprovalGate(Decision.PENDING, request.getRequestId());
            }
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

    /**
     * J4（插入点 ①）：阶段产出后经决策闸门判定走向。
     * <p>未配置闸门 / 决策平面缺失 / 判定异常 → {@link GateRoute#RUN_NEXT}（默认固定顺序，P10）；
     * 低置信/答案缺失 → FAIL_CLOSED，消费侧同样回落默认顺序（P12③最保守分支＝原确定性序列）。
     * 每次判定发 {@code decision_route_counts} 计数（DECISION 维度，经 H5）。</p>
     */
    private GateRoute evaluateStageGate(ExecutionTask task, WorkflowStage stage, String answer) {
        if (decisionPort == null || !stage.hasDecisionGate()) {
            return GateRoute.RUN_NEXT;
        }
        StageDecisionGate gate = stage.getDecisionGate();
        String state = "工作流阶段: " + stage.getId()
                + "\n阶段指令: " + stage.getInstruction()
                + "\n阶段产出: " + truncate(answer, 2000);
        DecisionAnswer decision = decideQuietly(state, gate.toQuestion());
        GateRoute route = GateRoute.of(decision, gate.threshold());
        emitRouteCount(route.name().toLowerCase(java.util.Locale.ROOT));
        return route;
    }

    /** J4：恢复续跑时检查上一阶段闸门升级的人审单；DENIED/PENDING → 阻断（只收紧，P12②），其余 null。 */
    private GateApprovalBlock findBlockingGateApproval(ExecutionTask task, String gateStageId) {
        Optional<ApprovalRequest> request =
                approvalPort.findLatest(task.getTaskId(), "workflow-gate:" + gateStageId);
        if (request.isEmpty()) {
            return null;
        }
        ApprovalStatus status = request.get().getStatus();
        if (status == ApprovalStatus.PENDING) {
            return new GateApprovalBlock(true, request.get().getRequestId());
        }
        if (status == ApprovalStatus.DENIED) {
            return new GateApprovalBlock(false, request.get().getRequestId());
        }
        return null;
    }

    /**
     * J6（插入点 ③）：产物验收闸门——{@code addArtifact} 前用 {@code score} 判完整/合规。
     * <p>安全分与置信均 ≥ {@code artifact-accept}（0.80）→ 接受；否则<b>有界重试</b>该阶段
     * （至多 {@value #MAX_ARTIFACT_RETRIES} 次，受终止闸门约束不无限重试）；重试仍不达标或重试失败 →
     * 打标 {@code accepted=false} + 原因，<b>不阻断</b>工作流。决策平面缺失 → 原样接受（v2.0 行为，P10）。</p>
     */
    private Artifact reviewArtifact(ExecutionTask master, Agent agent, WorkflowStage stage, StageOutcome first) {
        String name = stage.getArtifactName();
        String type = stage.getArtifactType();
        if (decisionPort == null) {
            return Artifact.of(name, type, first.answer);
        }
        StageOutcome outcome = first;
        for (int attempt = 0; ; attempt++) {
            DecisionAnswer answer = decideQuietly(artifactState(stage, outcome.answer),
                    DecisionQuestion.score("artifact-accept",
                            "评估该阶段产物的完整性/合规性得分（0-1，越完整合规越高）"));
            boolean accepted = answer != null
                    && answer.confidence() >= decisionThresholds.artifactAccept()
                    && answer.value() >= decisionThresholds.artifactAccept();
            emitRouteCount(accepted ? "artifact_accept" : "artifact_reject");
            if (accepted) {
                return Artifact.reviewed(name, type, outcome.answer, true, String.format(
                        "J6 accepted: score=%.3f confidence=%.3f >= %.2f",
                        answer.value(), answer.confidence(), decisionThresholds.artifactAccept()));
            }
            if (attempt >= MAX_ARTIFACT_RETRIES || master.gateTripped()) {
                return Artifact.reviewed(name, type, outcome.answer, false,
                        answer == null
                                ? "J6 rejected: no decision answer (fail-closed), threshold="
                                        + decisionThresholds.artifactAccept()
                                : String.format("J6 rejected: score=%.3f confidence=%.3f below threshold %.2f",
                                        answer.value(), answer.confidence(), decisionThresholds.artifactAccept()));
            }
            publish(master, "progress", "artifact " + name + " below accept threshold, bounded retry "
                    + (attempt + 1) + "/" + MAX_ARTIFACT_RETRIES);
            StageOutcome retry = runStage(master, agent, stage);
            master.addTokens(retry.tokens);
            if (!retry.success) {
                return Artifact.reviewed(name, type, outcome.answer, false,
                        "J6 rejected: bounded retry failed - " + retry.reason);
            }
            outcome = retry;
        }
    }

    /** 静默判定：异常/缺失一律返回 null（消费方走兜底，P10），绝不让决策平面异常中断工作流。 */
    private DecisionAnswer decideQuietly(String state, DecisionQuestion question) {
        if (decisionPort == null) {
            return null;
        }
        try {
            DecisionResponse response = decisionPort.decide(DecisionRequest.of(state, question));
            return response == null ? null : response.answer(question.key());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 分流计数进 {@code decision_route_counts}（DECISION 维度，经 H5 → Micrometer 事件计数）。 */
    private void emitRouteCount(String action) {
        if (evaluationService == null) {
            return;
        }
        try {
            ExecutionMetrics metrics = ExecutionMetrics.of("decision-plane");
            metrics.record(MetricDimension.DECISION, "decision_route_counts", action);
            evaluationService.report(metrics);
        } catch (RuntimeException ignored) {
            // P10：埋点失败静默丢弃
        }
    }

    private String approvalState(WorkflowStage stage, String goal) {
        return "审批阶段: " + stage.getId()
                + "\n阶段指令: " + stage.getInstruction()
                + "\n任务目标: " + truncate(goal, 1000);
    }

    private String artifactState(WorkflowStage stage, String content) {
        return "产物阶段: " + stage.getId()
                + "\n产物名: " + stage.getArtifactName()
                + "\n产物正文: " + truncate(content, 4000);
    }

    private static int indexOfStage(List<WorkflowStage> stages, String stageId) {
        if (stageId == null || stageId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < stages.size(); i++) {
            if (stageId.equals(stages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
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

    /** J4：闸门升级人审单的阻断状态（pending=true 挂起 / false 拒绝）。 */
    private record GateApprovalBlock(boolean pending, String requestId) {
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
