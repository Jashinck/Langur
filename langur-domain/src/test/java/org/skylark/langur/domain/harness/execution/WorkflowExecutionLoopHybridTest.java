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
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J7 验收 - Hybrid 执行器择优 + 升/降级触发（插入点 ④⑤，纯 JUnit5 桩，无 Mockito）。
 * <p>④：HYBRID 每阶段经 {@code choice} 选"够用的最便宜执行器"——简单→ReAct、复杂→Plan（桩执行器断言调用）；
 * 低置信/缺失回退构造期范式（P10）。⑤：仅廉价 ReAct 路径上，{@code noul} 判"已达成"→提前成功终止、
 * 判"重复卡死"→升级 Plan 重跑（无升级路径则终止）；低置信→PROCEED，回退既有 LoopDetector/闸门（P12③）。
 * 决策平面缺失＝v2.0 固定中层 Plan 委派（P12①）。分流计数进 {@code decision_route_counts}。</p>
 */
class WorkflowExecutionLoopHybridTest {

    private static final TerminationGate GATE = TerminationGate.defaults();

    // ---------- J7④：每阶段执行器择优 ----------

    @Test
    void shouldRouteSimpleStageToReactAndComplexStageToPlan() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(
                        WorkflowStage.of("s1", "简单抽取字段"),
                        WorkflowStage.of("s2", "复杂多步规划拆解"))));
        StubStageExecutor react = new StubStageExecutor(Set.of(), 1L);
        StubStageExecutor plan = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = hybridLoop(repo, react, plan, eval,
                port(req -> asks(req, "stage-executor")
                        ? choice("stage-executor", req.state().contains("简单") ? "react" : "plan", 0.9)
                        : DecisionResponse.of(Map.of())));

        ExecutionTask task = runHybrid(loop, agent("goal"), "hybrid-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("简单抽取字段"), react.subGoals, "简单阶段被择优到 ReAct（最便宜执行器）");
        assertEquals(List.of("复杂多步规划拆解"), plan.subGoals, "复杂阶段被择优到 Plan");
        assertEquals(1, react.calls);
        assertEquals(1, plan.calls);
        assertTrue(hasRouteCount(eval, "stage_react"));
        assertTrue(hasRouteCount(eval, "stage_plan"));
    }

    @Test
    void shouldFallBackToConfiguredParadigmOnLowConfidenceChoice() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(WorkflowStage.of("s1", "简单抽取"))));
        StubStageExecutor react = new StubStageExecutor(Set.of(), 1L);
        StubStageExecutor plan = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        // ④ 置信 0.3 < routing 0.75 → 回退构造期 stageParadigm（HYBRID 缺省 PLAN_AND_EXECUTE）
        WorkflowExecutionLoop loop = hybridLoop(repo, react, plan, eval,
                port(req -> asks(req, "stage-executor")
                        ? choice("stage-executor", "react", 0.3)
                        : DecisionResponse.of(Map.of())));

        ExecutionTask task = runHybrid(loop, agent("goal"), "hybrid-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(0, react.calls, "低置信 fail-closed → 不走 ReAct 廉价路径");
        assertEquals(1, plan.calls, "回退到构造期中层 Plan 范式（P10）");
        assertTrue(hasRouteCount(eval, "stage_executor_fallback"));
    }

    // ---------- J7⑤：升/降级触发 ----------

    @Test
    void shouldTerminateEarlyWhenNoulJudgesTaskComplete() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(
                        WorkflowStage.of("s1", "简单抽取"),
                        WorkflowStage.of("s2", "昂贵收尾阶段"))));
        StubStageExecutor react = new StubStageExecutor(Set.of(), 1L);
        StubStageExecutor plan = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = hybridLoop(repo, react, plan, eval,
                port(req -> asks(req, "stage-executor")
                        ? choice("stage-executor", "react", 0.9)
                        : DecisionResponse.of(Map.of("stage-complete",
                                DecisionAnswer.ofProbability(0.95, 0.95)))));

        ExecutionTask task = runHybrid(loop, agent("goal"), "hybrid-flow");

        // noul 判整体已达成（值+置信均 ≥ completion 0.85）→ 提前成功终止，跳过剩余昂贵阶段（提效）
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(List.of("简单抽取"), react.subGoals, "s2 昂贵阶段未被执行");
        assertEquals(1, react.calls);
        assertEquals(0, plan.calls);
        assertTrue(hasRouteCount(eval, "early_complete"));
    }

    @Test
    void shouldUpgradeReactToPlanWhenNoulJudgesStuck() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(WorkflowStage.of("s1", "复杂处理"))));
        StubStageExecutor react = new StubStageExecutor(Set.of(0), 1L); // ReAct 首跑失败（卡死）
        StubStageExecutor plan = new StubStageExecutor(Set.of(), 1L);   // 升级 Plan 后成功
        RecordingEvaluation eval = new RecordingEvaluation();
        WorkflowExecutionLoop loop = hybridLoop(repo, react, plan, eval,
                port(req -> asks(req, "stage-executor")
                        ? choice("stage-executor", "react", 0.9)
                        : DecisionResponse.of(Map.of("stage-stuck",
                                DecisionAnswer.ofProbability(0.95, 0.95)))));

        ExecutionTask task = runHybrid(loop, agent("goal"), "hybrid-flow");

        // noul 判重复卡死 → 升级 ReAct→Plan 重跑（接既有 replan-on-failure），最终成功
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(1, react.calls, "ReAct 廉价路径先跑一次");
        assertEquals(1, plan.calls, "卡死触发升级到中层 Plan 重跑");
        assertTrue(hasRouteCount(eval, "upgrade_plan"));
    }

    @Test
    void shouldFallBackToExistingGateOnLowConfidenceNoul() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(
                        WorkflowStage.of("s1", "处理一"),
                        WorkflowStage.of("s2", "处理二"))));
        StubStageExecutor react = new StubStageExecutor(Set.of(), 1L);
        StubStageExecutor plan = new StubStageExecutor(Set.of(), 1L);
        RecordingEvaluation eval = new RecordingEvaluation();
        // ④ 高置信走 ReAct；⑤ noul 低置信（0.3 < 0.85）→ PROCEED，不升级/不提前终止
        WorkflowExecutionLoop loop = hybridLoop(repo, react, plan, eval,
                port(req -> asks(req, "stage-executor")
                        ? choice("stage-executor", "react", 0.9)
                        : DecisionResponse.of(Map.of(
                                "stage-complete", DecisionAnswer.ofProbability(0.99, 0.3),
                                "stage-stuck", DecisionAnswer.ofProbability(0.99, 0.3)))));

        ExecutionTask task = runHybrid(loop, agent("goal"), "hybrid-flow");

        // 低置信 → 回退既有 LoopDetector/TerminationGate 指纹逻辑（P12③）：两阶段照常顺序执行
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(2, react.calls, "无 J7⑤ 动作，两阶段均走 ReAct 廉价路径并完成");
        assertEquals(0, plan.calls, "未触发升级");
    }

    @Test
    void shouldKeepV2HybridBehaviorWhenDecisionPortAbsent() {
        DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
                WorkflowDefinition.of("hybrid-flow", "混合流程", List.of(
                        WorkflowStage.of("s1", "处理一"),
                        WorkflowStage.of("s2", "处理二"))));
        StubStageExecutor react = new StubStageExecutor(Set.of(), 1L);
        StubStageExecutor plan = new StubStageExecutor(Set.of(), 1L);

        // 未 attach 决策平面（enabled=false 缺省态）→ ④⑤ 不生效，每阶段固定委派中层 Plan（v2.0 行为，P12①）
        ExecutionTask task = runHybrid(hybridLoop(repo, react, plan, new RecordingEvaluation(), null),
                agent("goal"), "hybrid-flow");

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(0, react.calls, "决策平面缺失 → 不择优 ReAct");
        assertEquals(2, plan.calls, "固定中层 Plan 委派（与 v2.0 一致）");
    }

    // ---------- 夹具 ----------

    private static boolean asks(DecisionRequest request, String key) {
        return request.questions().stream().anyMatch(q -> key.equals(q.key()));
    }

    private static StubDecisionPort port(Function<DecisionRequest, DecisionResponse> responder) {
        return new StubDecisionPort(responder);
    }

    private static DecisionResponse choice(String key, String value, double confidence) {
        return DecisionResponse.of(Map.of(key, DecisionAnswer.ofChoice(value, confidence, Map.of())));
    }

    /** Hybrid 循环：构造期中层 Plan 委派 + 每阶段执行器池 {REACT, PLAN_AND_EXECUTE}（J7④）。 */
    private WorkflowExecutionLoop hybridLoop(DefaultWorkflowRepository repo, ExecutionLoopService reactStub,
                                             ExecutionLoopService planStub, EvaluationService eval,
                                             DecisionPort decisionPort) {
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(planStub, RuntimeParadigm.PLAN_AND_EXECUTE, repo,
                new MapApprovalPort(), new MapTaskStateRepository(), eval, new LifecycleHookEngine());
        Map<RuntimeParadigm, ExecutionLoopService> executors = new EnumMap<>(RuntimeParadigm.class);
        executors.put(RuntimeParadigm.REACT, reactStub);
        executors.put(RuntimeParadigm.PLAN_AND_EXECUTE, planStub);
        loop.attachStageExecutors(executors);
        if (decisionPort != null) {
            loop.attachDecisionPlane(decisionPort, null);
        }
        return loop;
    }

    private ExecutionTask runHybrid(WorkflowExecutionLoop loop, Agent agent, String bizCode) {
        ExecutionTask task = ExecutionTask.create(agent.getId().getValue(), bizCode,
                RuntimeParadigm.HYBRID, GATE);
        return loop.execute(task, agent, new Plan(agent.getId().getValue()));
    }

    private Agent agent(String goal) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("HybridAgent"));
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

    /** 决策端口桩：按脚本函数应答并记录请求（domain 测试禁用 Mockito）。 */
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

    /** 阶段执行器桩：按调用序号决定成功/失败，记录子目标，成功写 assistant 消息并上卷 token。 */
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
