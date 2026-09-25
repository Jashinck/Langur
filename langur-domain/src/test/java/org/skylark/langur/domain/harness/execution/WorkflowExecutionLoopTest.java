package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.security.ApprovalStatus;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.StepStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H9 验收 - Workflow 引擎（Harness 全权）：确定性阶段按序执行、审批闸门挂起-批准-恢复、
 * 无审批后端 fail-closed、阶段失败即中断、终止闸门对总 Token 生效、路由/分层决策有指标支撑；
 * Hybrid 经中层 PlanAndExecute 委派（分层分权）。
 */
class WorkflowExecutionLoopTest {

    private static final TerminationGate GATE = TerminationGate.defaults();

    @Test
    void shouldExecuteWorkflowStagesInOrderAndComplete() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("order-flow", "订单流程", List.of(
                        WorkflowStage.of("validate", "校验订单"),
                        WorkflowStage.of("fulfill", "履约发货"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 5L);
        Agent agent = agent("下单");
        Plan plan = new Plan("a");

        ExecutionTask task = run(loop(exec, RuntimeParadigm.REACT, repo, new MapApprovalPort()),
                agent, plan, "order-flow", RuntimeParadigm.WORKFLOW, GATE);

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("校验订单", "履约发货"), exec.subGoals);
        assertEquals(2, plan.getSteps().size());
        assertTrue(plan.getSteps().stream().allMatch(s -> s.getStatus() == StepStatus.COMPLETED));
        assertEquals(2, task.getCurrentRound());
        assertEquals(10L, task.getConsumedTokens());
    }

    @Test
    void shouldDelegateToStageExecutorWithConfiguredParadigm() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程",
                        List.of(WorkflowStage.of("s1", "复杂子任务"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        Agent agent = agent("goal");

        // Hybrid：阶段委派中层 PlanAndExecute（分层分权）
        run(loop(exec, RuntimeParadigm.PLAN_AND_EXECUTE, repo, new MapApprovalPort()),
                agent, new Plan("a"), "hybrid-flow", RuntimeParadigm.HYBRID, GATE);
        assertEquals(List.of(RuntimeParadigm.PLAN_AND_EXECUTE), exec.subParadigms);

        // Workflow：阶段委派底层 ReAct（全权控制）
        StubStageExecutor exec2 = new StubStageExecutor(Set.of(), 1L);
        run(loop(exec2, RuntimeParadigm.REACT, repo, new MapApprovalPort()),
                agent("goal"), new Plan("a"), "order-flow", RuntimeParadigm.WORKFLOW, GATE);
        assertEquals(List.of(RuntimeParadigm.REACT), exec2.subParadigms);
    }

    @Test
    void shouldSuspendOnApprovalGateThenResumeAfterApproval() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("compliance-flow", "合规流程", List.of(
                        WorkflowStage.approval("review", "人工审核"),
                        WorkflowStage.of("act", "执行操作"))));
        MapApprovalPort approvals = new MapApprovalPort();
        MapTaskStateRepository stateRepo = new MapTaskStateRepository();
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 3L);
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(exec, RuntimeParadigm.REACT, repo, approvals,
                stateRepo, new RecordingEvaluation(), new LifecycleHookEngine());
        Agent agent = agent("敏感操作");

        ExecutionTask task = ExecutionTask.create(agent.getId().getValue(), "compliance-flow",
                RuntimeParadigm.WORKFLOW, GATE);
        loop.execute(task, agent, new Plan(agent.getId().getValue()));

        // 首跑：审批闸门挂起，未执行任何阶段
        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("awaiting approval"));
        assertEquals(0, exec.calls);
        assertEquals(TaskStateStatus.SUSPENDED, stateRepo.findById(task.getTaskId()).orElseThrow().getStatus());

        // 审批通过（等价 ApprovalService.approve）
        ApprovalRequest request = approvals.findLatest(task.getTaskId(), "workflow:review").orElseThrow();
        request.approve("boss", "ok");

        // 断点续跑：同 taskId 重入，审批已批准 → 两阶段执行完成
        ExecutionTask resumed = ExecutionTask.resume(task.getTaskId(), agent.getId().getValue(),
                "compliance-flow", RuntimeParadigm.WORKFLOW, GATE);
        loop.execute(resumed, agent, new Plan(agent.getId().getValue()));

        assertEquals(ExecutionStatus.COMPLETED, resumed.getStatus());
        assertEquals(2, exec.calls);
        assertEquals(List.of("人工审核", "执行操作"), exec.subGoals);
    }

    @Test
    void shouldFailClosedWhenApprovalRequiredButNoBackend() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("compliance-flow", "合规流程",
                        List.of(WorkflowStage.approval("review", "人工审核"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        Agent agent = agent("goal");

        // 无审批后端（null）→ fail-closed，不放行
        ExecutionTask task = run(loop(exec, RuntimeParadigm.REACT, repo, null),
                agent, new Plan("a"), "compliance-flow", RuntimeParadigm.WORKFLOW, GATE);

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("no approval backend configured"));
        assertEquals(0, exec.calls);
    }

    @Test
    void shouldTerminateWhenApprovalDenied() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("compliance-flow", "合规流程",
                        List.of(WorkflowStage.approval("review", "人工审核"))));
        MapApprovalPort approvals = new MapApprovalPort();
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        Agent agent = agent("goal");
        WorkflowExecutionLoop loop = loop(exec, RuntimeParadigm.REACT, repo, approvals);

        ExecutionTask task = ExecutionTask.create(agent.getId().getValue(), "compliance-flow",
                RuntimeParadigm.WORKFLOW, GATE);
        // 预置一条 DENIED 审批单
        ApprovalRequest denied = ApprovalRequest.of("r1", task.getTaskId(), "workflow:compliance-flow",
                "workflow:review", "denied ahead");
        denied.deny("boss", "no");
        approvals.save(denied);

        loop.execute(task, agent, new Plan("a"));

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("approval denied"));
        assertEquals(0, exec.calls);
    }

    @Test
    void shouldTerminateWhenStageFails() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("order-flow", "订单流程", List.of(
                        WorkflowStage.of("s1", "会失败的阶段"),
                        WorkflowStage.of("s2", "不应执行"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(0), 1L);
        Agent agent = agent("goal");

        ExecutionTask task = run(loop(exec, RuntimeParadigm.REACT, repo, new MapApprovalPort()),
                agent, new Plan("a"), "order-flow", RuntimeParadigm.WORKFLOW, GATE);

        // Harness 全权：阶段失败即中断，无重规划，后续阶段不执行
        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("Workflow stage failed: s1"));
        assertEquals(1, exec.calls);
    }

    @Test
    void shouldEnforceTokenGateAcrossStages() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("order-flow", "订单流程", List.of(
                        WorkflowStage.of("s1", "a"), WorkflowStage.of("s2", "b"), WorkflowStage.of("s3", "c"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1000L);
        TerminationGate gate = TerminationGate.builder()
                .maxRounds(10).maxTokens(500L)
                .maxTimeout(java.time.Duration.ofMinutes(5)).maxCallsPerRound(5).build();
        Agent agent = agent("goal");

        ExecutionTask task = run(loop(exec, RuntimeParadigm.REACT, repo, new MapApprovalPort()),
                agent, new Plan("a"), "order-flow", RuntimeParadigm.WORKFLOW, gate);

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Termination gate tripped", task.getTerminateReason());
        assertEquals(1, exec.calls, "闸门触发后不应继续执行后续阶段");
    }

    @Test
    void shouldUseFallbackPassthroughWhenNoDefinition() {
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        Agent agent = agent("开放问答");

        // 无注册定义 + 非合规 bizCode → 合成单阶段直通工作流
        ExecutionTask task = run(loop(exec, RuntimeParadigm.REACT, new DefaultWorkflowRepository(), null),
                agent, new Plan("a"), "default", RuntimeParadigm.WORKFLOW, GATE);

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(1, exec.calls);
        assertEquals(List.of("开放问答"), exec.subGoals);
    }

    @Test
    void shouldReportLayerRoutingMetrics() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("order-flow", "订单流程", List.of(WorkflowStage.of("s1", "x"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(exec, RuntimeParadigm.REACT, repo,
                new MapApprovalPort(), new MapTaskStateRepository(), eval, new LifecycleHookEngine());
        Agent agent = agent("goal");

        run(loop, agent, new Plan("a"), "order-flow", RuntimeParadigm.WORKFLOW, GATE);

        assertEquals(1, eval.metrics.size());
        Map<String, Object> scheduling = eval.metrics.get(0).getDimensions().get(MetricDimension.SCHEDULING);
        assertEquals("WORKFLOW", scheduling.get("paradigm"));
        assertEquals("WORKFLOW_LAYER", scheduling.get("layer"));
        assertEquals("REACT", scheduling.get("stageExecutor"));
    }

    @Test
    void shouldReportHybridLayerMetricForHybridParadigm() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(WorkflowStage.of("s1", "x"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(exec, RuntimeParadigm.PLAN_AND_EXECUTE, repo,
                new MapApprovalPort(), new MapTaskStateRepository(), eval, new LifecycleHookEngine());

        run(loop, agent("goal"), new Plan("a"), "hybrid-flow", RuntimeParadigm.HYBRID, GATE);

        Map<String, Object> scheduling = eval.metrics.get(0).getDimensions().get(MetricDimension.SCHEDULING);
        assertEquals("HYBRID", scheduling.get("paradigm"));
        assertEquals("HYBRID_LAYER", scheduling.get("layer"), "HYBRID 不应被误标为 WORKFLOW_LAYER");
    }

    @Test
    void shouldRecordNamedArtifactsFromDeclaringStages() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("contract-review", "合同审查", List.of(
                        WorkflowStage.of("ingest", "读取合同与尽调"),
                        WorkflowStage.artifact("review", "出具审查结论", "review-report", "report"),
                        WorkflowStage.artifact("special", "梳理特批项", "approval-items", "approval-items"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);

        ExecutionTask task = run(loop(exec, RuntimeParadigm.REACT, repo, new MapApprovalPort()),
                agent("合同审查"), new Plan("a"), "contract-review", RuntimeParadigm.WORKFLOW, GATE);

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        List<Artifact> artifacts = task.getArtifacts();
        assertEquals(2, artifacts.size(), "仅声明产物的两个阶段产出 artifact");
        assertEquals("review-report", artifacts.get(0).getName());
        assertEquals("report", artifacts.get(0).getType());
        assertEquals("done-1", artifacts.get(0).getContent());
        assertEquals("approval-items", artifacts.get(1).getName());
        assertEquals("done-2", artifacts.get(1).getContent());
    }

    private WorkflowExecutionLoop loop(ExecutionLoopService stageExecutor, RuntimeParadigm stageParadigm,
                                       DefaultWorkflowRepository repo, ApprovalPort approvalPort) {
        return new WorkflowExecutionLoop(stageExecutor, stageParadigm, repo, approvalPort,
                new MapTaskStateRepository(), new RecordingEvaluation(), new LifecycleHookEngine());
    }

    private ExecutionTask run(WorkflowExecutionLoop loop, Agent agent, Plan plan,
                              String bizCode, RuntimeParadigm paradigm, TerminationGate gate) {
        ExecutionTask task = ExecutionTask.create(agent.getId().getValue(), bizCode, paradigm, gate);
        return loop.execute(task, agent, plan);
    }

    private Agent agent(String goal) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("WorkflowAgent"));
        agent.markRunning();
        agent.addUserMessage(goal);
        return agent;
    }

    /** 阶段执行器桩：按调用序号决定成功/失败，记录子任务范式与子目标，成功时写 assistant 消息并上卷 token。 */
    private static class StubStageExecutor implements ExecutionLoopService {
        final List<String> subGoals = new ArrayList<>();
        final List<RuntimeParadigm> subParadigms = new ArrayList<>();
        private final Set<Integer> failingCalls;
        private final long tokensPerStage;
        int calls = 0;

        StubStageExecutor(Set<Integer> failingCalls, long tokensPerStage) {
            this.failingCalls = new HashSet<>(failingCalls);
            this.tokensPerStage = tokensPerStage;
        }

        @Override
        public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
            int idx = calls++;
            task.start();
            task.nextRound();
            task.addTokens(tokensPerStage);
            subParadigms.add(task.getParadigm());
            subGoals.add(lastUser(agent));
            if (failingCalls.contains(idx)) {
                task.terminate("stub stage failure #" + idx);
            } else {
                agent.addAssistantMessage("done-" + idx);
                task.complete();
            }
            return task;
        }

        private String lastUser(Agent agent) {
            List<Map<String, String>> history = agent.getConversationHistory();
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    String content = history.get(i).get("content");
                    int marker = content.indexOf("] ");
                    return content.startsWith("[工作流阶段") && marker > 0
                            ? content.substring(marker + 2) : content;
                }
            }
            return "";
        }
    }

    /** 审批端口桩：内存态，按 (traceId=taskId, toolId) 保留最新单。 */
    private static class MapApprovalPort implements ApprovalPort {
        private final List<ApprovalRequest> store = new ArrayList<>();

        @Override
        public void save(ApprovalRequest request) {
            store.add(request);
        }

        @Override
        public Optional<ApprovalRequest> findById(String requestId) {
            return store.stream().filter(r -> r.getRequestId().equals(requestId)).findFirst();
        }

        @Override
        public Optional<ApprovalRequest> findLatest(String traceId, String toolId) {
            return store.stream()
                    .filter(r -> r.getTraceId().equals(traceId) && r.getToolId().equals(toolId))
                    .max(Comparator.comparing(ApprovalRequest::getCreatedAt));
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
