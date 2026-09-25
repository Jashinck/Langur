package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.state.TaskStateStatus;
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.StageDecisionGate;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J4–J6 验收 - Workflow × 决策平面（插入点 ①②③，纯 JUnit5 桩，无 Mockito）。
 * <p>J4：高置信 skip 跳过下一阶段（昂贵阶段未被调用）、branch 跳转、abort 中断、require-approval 升级人审
 * （挂起-批准-恢复）；低置信/无端口回落默认顺序（P10/P12③）。J5：非 CRITICAL 高分自动放行、中分/低置信人审；
 * <b>CRITICAL 恒人审且绝不咨询决策平面</b>（P12②）。J6：高分直接接受、低分一次有界重试后打标不阻断、
 * 无端口不验收（v2.0 行为）。分流计数进 {@code decision_route_counts}。</p>
 */
class WorkflowExecutionLoopDecisionGateTest {

    private static final TerminationGate GATE = TerminationGate.defaults();

    // ---------- J4：阶段决策闸门（插入点 ①） ----------

    @Test
    void shouldSkipNextStageOnHighConfidenceSkipGate() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("gate-flow", "闸门流程", List.of(
                        stageWithGate("s1", "读取输入", gateChoice(null)),
                        WorkflowStage.of("s2", "昂贵LLM阶段"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 5L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = loop(exec, repo, new MapApprovalPort(), eval,
                port(request -> choiceResponse("gate", "skip", 0.9)));

        ExecutionTask task = run(loop, agent("goal"), "gate-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("读取输入"), exec.subGoals, "高置信 skip：下一阶段（昂贵 LLM）未被调用");
        assertEquals(1, exec.calls);
        assertTrue(hasRouteCount(eval, "skip"), "分流计数进 decision_route_counts");
    }

    @Test
    void shouldBranchToTargetStageOnHighConfidenceBranch() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("gate-flow", "闸门流程", List.of(
                        stageWithGate("s1", "分诊", gateChoice("s3", 0.75)),
                        WorkflowStage.of("s2", "常规处理"),
                        WorkflowStage.of("s3", "专项处理"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        WorkflowExecutionLoop loop = loop(exec, repo, new MapApprovalPort(), new RecordingEvaluation(),
                port(request -> choiceResponse("gate", "branch-to-stage", 0.8)));

        ExecutionTask task = run(loop, agent("goal"), "gate-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("分诊", "专项处理"), exec.subGoals, "高置信 branch 跳转到目标阶段");
    }

    @Test
    void shouldTerminateOnHighConfidenceAbortGate() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("gate-flow", "闸门流程", List.of(
                        stageWithGate("s1", "风控检查", gateChoice(null)),
                        WorkflowStage.of("s2", "不应执行"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        WorkflowExecutionLoop loop = loop(exec, repo, new MapApprovalPort(), new RecordingEvaluation(),
                port(request -> choiceResponse("gate", "abort", 0.99)));

        ExecutionTask task = run(loop, agent("goal"), "gate-flow");

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("aborted by decision gate"));
        assertEquals(1, exec.calls);
    }

    @Test
    void shouldEscalateToHumanApprovalAndResumeAfterApprove() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("gate-flow", "闸门流程", List.of(
                        stageWithGate("s1", "生成变更", gateChoice(null)),
                        WorkflowStage.of("s2", "落地变更"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        MapApprovalPort approvals = new MapApprovalPort();
        MapTaskStateRepository stateRepo = new MapTaskStateRepository();
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(exec, RuntimeParadigm.REACT, repo, approvals,
                stateRepo, new RecordingEvaluation(), new LifecycleHookEngine());
        loop.attachDecisionPlane(port(request -> choiceResponse("gate", "require-approval", 0.9)), null);
        Agent agent = agent("goal");

        ExecutionTask task = run(loop, agent, "gate-flow");

        // 首跑：闸门升级人审 → 挂起，s2 未执行
        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("awaiting approval: decision-gate s1"));
        assertEquals(TaskStateStatus.SUSPENDED, stateRepo.findById(task.getTaskId()).orElseThrow().getStatus());
        assertEquals(1, exec.calls);
        ApprovalRequest request = approvals.findLatest(task.getTaskId(), "workflow-gate:s1").orElseThrow();
        assertTrue(request.isPending());

        // 人审批准后恢复续跑：不得再次越过人审校验，s2 执行完成
        request.approve("boss", "ok");
        ExecutionTask resumed = ExecutionTask.resume(task.getTaskId(), agent.getId().getValue(),
                "gate-flow", RuntimeParadigm.WORKFLOW, GATE);
        loop.execute(resumed, agent, new Plan(agent.getId().getValue()));

        assertEquals(ExecutionStatus.COMPLETED, resumed.getStatus());
        assertEquals(List.of("生成变更", "落地变更"), exec.subGoals);
    }

    @Test
    void shouldFallBackToDefaultOrderOnLowConfidenceGate() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("gate-flow", "闸门流程", List.of(
                        stageWithGate("s1", "a", gateChoice(null)),
                        WorkflowStage.of("s2", "b"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = loop(exec, repo, new MapApprovalPort(), eval,
                port(request -> choiceResponse("gate", "skip", 0.3)));

        ExecutionTask task = run(loop, agent("goal"), "gate-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("a", "b"), exec.subGoals, "低置信 fail-closed → 默认固定顺序（P12③）");
        assertTrue(hasRouteCount(eval, "fail_closed"));
    }

    @Test
    void shouldKeepV2BehaviorWhenDecisionPortAbsent() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("gate-flow", "闸门流程", List.of(
                        stageWithGate("s1", "a", gateChoice(null)),
                        WorkflowStage.of("s2", "b"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);

        // 未 attach 决策平面（enabled=false 缺省态）→ 闸门不生效，行为与 v2.0 一致（P12①）
        ExecutionTask task = run(loop(exec, repo, new MapApprovalPort(), new RecordingEvaluation(), null),
                agent("goal"), "gate-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("a", "b"), exec.subGoals);
    }

    // ---------- J5：审批风险分级（插入点 ②，非 CRITICAL；CRITICAL 恒人审） ----------

    @Test
    void shouldAutoPassNonCriticalApprovalOnHighScore() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("approval-flow", "审批流程", List.of(
                        WorkflowStage.approval("review", "特批项审批"),
                        WorkflowStage.of("act", "执行"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        MapApprovalPort approvals = new MapApprovalPort();
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = loop(exec, repo, approvals, eval,
                port(request -> scoreResponse("approval-auto", 0.95, 0.95)));

        ExecutionTask task = run(loop, agent("goal"), "approval-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus(), "非 CRITICAL 高分高置信 → 策略内自动放行快路");
        assertEquals(2, exec.calls);
        ApprovalRequest request = approvals.findLatest(task.getTaskId(), "workflow:review").orElseThrow();
        assertTrue(request.isApproved());
        assertEquals("decision-plane", request.getDecisionBy(), "自动放行留痕可溯源");
        assertTrue(hasRouteCount(eval, "approve"));
    }

    @Test
    void shouldSuspendForHumanReviewOnMediumScoreOrLowConfidence() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("approval-flow", "审批流程",
                        List.of(WorkflowStage.approval("review", "特批项审批"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();

        // 中分（0.6 < 0.90）→ 人审
        ExecutionTask medium = run(loop(exec, repo, new MapApprovalPort(), eval,
                        port(request -> scoreResponse("approval-auto", 0.6, 0.99))),
                agent("goal"), "approval-flow");
        assertEquals(ExecutionStatus.TERMINATED, medium.getStatus());
        assertTrue(medium.getTerminateReason().contains("awaiting approval"));
        assertEquals(0, exec.calls);

        // 高分但低置信（0.5 < 0.90）→ fail-closed 人审
        ExecutionTask lowConf = run(loop(exec, repo, new MapApprovalPort(), eval,
                        port(request -> scoreResponse("approval-auto", 0.99, 0.5))),
                agent("goal2"), "approval-flow");
        assertEquals(ExecutionStatus.TERMINATED, lowConf.getStatus());
        assertTrue(lowConf.getTerminateReason().contains("awaiting approval"));
        assertTrue(hasRouteCount(eval, "fail_closed"));
    }

    @Test
    void shouldAlwaysHumanReviewCriticalStageWithoutConsultingDecisionPlane() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("approval-flow", "审批流程",
                        List.of(WorkflowStage.approval("review", "CRITICAL 特批").withCritical(true))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        StubDecisionPort stub = port(request -> scoreResponse("approval-auto", 0.99, 0.99));

        ExecutionTask task = run(loop(exec, repo, new MapApprovalPort(), new RecordingEvaluation(), stub),
                agent("goal"), "approval-flow");

        // P12②红线：CRITICAL 无论评分多高都挂起人审，且绝不咨询决策平面
        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertTrue(task.getTerminateReason().contains("awaiting approval"));
        assertEquals(0, exec.calls);
        assertEquals(0, stub.requests.size(), "CRITICAL 恒人审：决策平面未被调用（只收紧不放松）");
    }

    // ---------- J6：产物验收闸门（插入点 ③） ----------

    @Test
    void shouldAcceptHighScoreArtifactDirectly() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("artifact-flow", "产物流程", List.of(
                        WorkflowStage.artifact("report", "出具报告", "review-report", "report"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        WorkflowExecutionLoop loop = loop(exec, repo, new MapApprovalPort(), new RecordingEvaluation(),
                port(request -> scoreResponse("artifact-accept", 0.9, 0.9)));

        ExecutionTask task = run(loop, agent("goal"), "artifact-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(1, exec.calls, "高分直接接受，不触发重试");
        Artifact artifact = task.getArtifacts().get(0);
        assertTrue(artifact.isAccepted());
        assertEquals("done-0", artifact.getContent());
    }

    @Test
    void shouldRetryOnceThenMarkRejectedWithoutBlocking() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("artifact-flow", "产物流程", List.of(
                        WorkflowStage.artifact("report", "出具报告", "review-report", "report"),
                        WorkflowStage.of("after", "后续阶段"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = loop(exec, repo, new MapApprovalPort(), eval,
                port(request -> scoreResponse("artifact-accept", 0.3, 0.9)));

        ExecutionTask task = run(loop, agent("goal"), "artifact-flow");

        // 低分 → 一次有界重试（阶段共执行 2 次）仍低分 → 打标 accepted=false，不阻断后续阶段
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(3, exec.calls, "1 次初执行 + 1 次有界重试（不超上限）+ 1 次后续阶段");
        Artifact artifact = task.getArtifacts().get(0);
        assertFalse(artifact.isAccepted());
        assertTrue(artifact.getReviewNote().contains("below threshold"));
        assertTrue(exec.subGoals.contains("后续阶段"), "打标不阻断");
        assertTrue(hasRouteCount(eval, "artifact_reject"));
    }

    @Test
    void shouldSkipArtifactReviewWhenDecisionPortAbsent() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("artifact-flow", "产物流程", List.of(
                        WorkflowStage.artifact("report", "出具报告", "review-report", "report"))));
        StubStageExecutor exec = new StubStageExecutor(Set.of(), 1L);

        ExecutionTask task = run(loop(exec, repo, new MapApprovalPort(), new RecordingEvaluation(), null),
                agent("goal"), "artifact-flow");

        assertEquals(1, exec.calls);
        assertTrue(task.getArtifacts().get(0).isAccepted(), "无决策平面 → 原样接受（v2.0 行为，P10）");
    }

    // ---------- 夹具 ----------

    private static StageDecisionGate gateChoice(String branchTo) {
        return gateChoice(branchTo, 0.75);
    }

    private static StageDecisionGate gateChoice(String branchTo, double threshold) {
        return StageDecisionGate.choice("gate", "阶段产出后应如何继续？",
                Map.of("run", "继续下一阶段", "skip", "下一阶段不必要",
                        "branch-to-stage", "跳转专项阶段", "abort", "应当中断",
                        "require-approval", "须升级人审"),
                threshold, branchTo);
    }

    private static WorkflowStage stageWithGate(String id, String instruction, StageDecisionGate gate) {
        return WorkflowStage.of(id, instruction).withDecisionGate(gate);
    }

    private static StubDecisionPort port(Function<DecisionRequest, DecisionResponse> responder) {
        return new StubDecisionPort(responder);
    }

    private static DecisionResponse choiceResponse(String key, String choice, double confidence) {
        return DecisionResponse.of(Map.of(key, DecisionAnswer.ofChoice(choice, confidence, Map.of())));
    }

    private static DecisionResponse scoreResponse(String key, double value, double confidence) {
        return DecisionResponse.of(Map.of(key, DecisionAnswer.ofScore(value, confidence)));
    }

    private WorkflowExecutionLoop loop(ExecutionLoopService stageExecutor, DefaultWorkflowRepository repo,
                                       ApprovalPort approvalPort, EvaluationService evaluation,
                                       DecisionPort decisionPort) {
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(stageExecutor, RuntimeParadigm.REACT, repo,
                approvalPort, new MapTaskStateRepository(), evaluation, new LifecycleHookEngine());
        if (decisionPort != null) {
            loop.attachDecisionPlane(decisionPort, null);
        }
        return loop;
    }

    private ExecutionTask run(WorkflowExecutionLoop loop, Agent agent, String bizCode) {
        ExecutionTask task = ExecutionTask.create(agent.getId().getValue(), bizCode,
                RuntimeParadigm.WORKFLOW, GATE);
        return loop.execute(task, agent, new Plan(agent.getId().getValue()));
    }

    private Agent agent(String goal) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("WorkflowAgent"));
        agent.markRunning();
        agent.addUserMessage(goal);
        return agent;
    }

    private static boolean hasRouteCount(RecordingEvaluation eval, String value) {
        return eval.metrics.stream()
                .map(m -> m.getDimensions().get(MetricDimension.DECISION))
                .filter(d -> d != null)
                .anyMatch(d -> value.equals(d.get("decision_route_counts")));
    }

    /** 决策端口桩：按脚本函数应答并记录全部请求（domain 测试禁用 Mockito，匿名/内部类桩）。 */
    private static final class StubDecisionPort implements DecisionPort {
        final List<DecisionRequest> requests = new ArrayList<>();
        private final Function<DecisionRequest, DecisionResponse> responder;

        StubDecisionPort(Function<DecisionRequest, DecisionResponse> responder) {
            this.responder = responder;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            requests.add(request);
            return responder.apply(request);
        }
    }

    /** 阶段执行器桩：按调用序号决定成功/失败，成功写 assistant 消息并上卷 token。 */
    private static class StubStageExecutor implements ExecutionLoopService {
        final List<String> subGoals = new ArrayList<>();
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
