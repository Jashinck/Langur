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
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J9 验收 - ReAct 底层循环的决策平面语义判定（插入点 ⑦⑧，纯 JUnit5 桩，无 Mockito）。
 * <p>⑦：{@code noul}("给定轨迹，任务是否已达成") 作为 {@code TerminationGate} 之外的<b>附加</b>早停信号，
 * 值+置信均 ≥ {@code completion}（0.85）→ 检查点提前成功终止（不替代四维硬约束）。
 * ⑧：{@code choice}("是否在重复同一无效动作") 高置信判 stuck → LOOP_DETECTED，补 {@code LoopDetector}
 * 指纹之外的语义判定。<b>红线</b>：网络判定只在检查点（每 N 轮）发起，绝不逐轮往返；低置信/缺失 →
 * 以既有指纹/闸门为准（P10/P12③）。决策平面缺失＝v2.0 行为（P12①）。分流计数进 {@code decision_route_counts}。</p>
 */
class ReActExecutionLoopDecisionTest {

    private static final TerminationGate GATE = TerminationGate.defaults();

    @Test
    void shouldTerminateEarlyWhenNoulJudgesTaskCompleteAtCheckpoint() {
        RecordingEvaluation eval = new RecordingEvaluation();
        // ⑦ 第 3 轮检查点：noul 判已达成（值 0.95 + 置信 0.95 均 ≥ 0.85）→ 提前成功终止
        ReActExecutionLoop loop = reactLoop(eval,
                port(req -> DecisionResponse.of(Map.of(
                        "task-complete", DecisionAnswer.ofProbability(0.95, 0.95),
                        "trajectory-stuck", DecisionAnswer.ofChoice("progressing", 0.9, Map.of())))));

        Agent agent = agent();
        ExecutionTask result = loop.execute(task(agent), agent, new Plan(agent.getId().getValue()));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus(), "noul 判已达成 → 提前成功终止");
        assertEquals(3, result.getCurrentRound(), "在第 3 轮检查点即早停，远早于 maxRounds=10");
        assertTrue(hasRouteCount(eval, "react_early_complete"));
    }

    @Test
    void shouldTerminateWithLoopDetectedWhenChoiceJudgesStuck() {
        RecordingEvaluation eval = new RecordingEvaluation();
        // ⑧ 第 3 轮检查点：choice 高置信判 stuck → LOOP_DETECTED（补指纹之外的语义卡死判定）
        ReActExecutionLoop loop = reactLoop(eval,
                port(req -> DecisionResponse.of(Map.of(
                        "task-complete", DecisionAnswer.ofProbability(0.1, 0.1),
                        "trajectory-stuck", DecisionAnswer.ofChoice("stuck", 0.95, Map.of())))));

        ExecutionTask result = run(loop);

        assertEquals(ExecutionStatus.TERMINATED, result.getStatus());
        assertEquals("LOOP_DETECTED", result.getTerminateReason(), "choice 判重复无效 → 语义卡死终止");
        assertTrue(hasRouteCount(eval, "react_stuck"));
    }

    @Test
    void shouldOnlyConsultDecisionPlaneAtCheckpointsAndProceedOnLowConfidence() {
        RecordingEvaluation eval = new RecordingEvaluation();
        StubDecisionPort decisionPort = port(req -> DecisionResponse.of(Map.of(
                // 值高但置信 0.3 < 0.85 → 低置信，既不早停也不判卡死（P12③ 回退既有闸门）
                "task-complete", DecisionAnswer.ofProbability(0.99, 0.3),
                "trajectory-stuck", DecisionAnswer.ofChoice("stuck", 0.3, Map.of()))));
        ReActExecutionLoop loop = reactLoop(eval, decisionPort);

        ExecutionTask result = run(loop);

        // 低置信 → PROCEED：不改变既有终止（跑满 maxIterations/maxRounds 后由闸门终止）
        assertEquals(ExecutionStatus.TERMINATED, result.getStatus());
        assertNotEquals("LOOP_DETECTED", result.getTerminateReason());
        // 红线：判定只在检查点（每 3 轮）发起，绝非逐轮往返
        assertTrue(decisionPort.calls > 0, "决策平面确被咨询");
        assertTrue(decisionPort.calls < result.getCurrentRound(),
                "检查点判定次数(" + decisionPort.calls + ") 远少于总轮次(" + result.getCurrentRound() + ")");
        assertEquals(3, decisionPort.calls, "10 轮内仅在第 3/6/9 轮检查点各判定一次");
    }

    @Test
    void shouldKeepV2TerminationWhenDecisionPortAbsent() {
        RecordingEvaluation eval = new RecordingEvaluation();
        // 未 attach 决策平面（enabled=false 缺省态）→ ⑦⑧ 不生效，跑满后由既有闸门终止（v2.0 行为，P12①）
        ReActExecutionLoop loop = reactLoop(eval, null);

        ExecutionTask result = run(loop);

        assertEquals(ExecutionStatus.TERMINATED, result.getStatus());
        assertTrue(!hasRouteCount(eval, "react_early_complete") && !hasRouteCount(eval, "react_stuck"),
                "无决策平面 → 不产生任何 J9 分流计数");
    }

    // ---------- 夹具 ----------

    /** 一致地构造 agent/task/plan 并执行（避免 task 与 agent 取自不同实例）。 */
    private ExecutionTask run(ReActExecutionLoop loop) {
        Agent agent = agent();
        return loop.execute(task(agent), agent, new Plan(agent.getId().getValue()));
    }

    /** 构造仅发起工具调用、永不给最终答案的 LLMPort，使循环必须靠闸门/决策平面终止。 */
    private static LLMPort endlessToolCall() {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.toolCall("thinking", "echo", Map.of("input", "x"));
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "";
            }
        };
    }

    private static Agent agent() {
        Agent agent = Agent.create(AgentConfig.defaultConfig("ReactDecisionAgent"));
        agent.registerTool(new Tool(ToolDefinition.of("echo", "echo tool", Map.of())) {
            @Override
            public ToolResult execute(Map<String, Object> parameters) {
                return ToolResult.success("same");
            }
        });
        agent.addUserMessage("run");
        return agent;
    }

    private static ExecutionTask task(Agent agent) {
        return ExecutionTask.create(agent.getId().getValue(), "default", RuntimeParadigm.REACT, GATE);
    }

    /** ReAct 循环：不挂 LoopDetector（隔离指纹路径），按缺省 3 轮检查点织入决策平面。 */
    private ReActExecutionLoop reactLoop(EvaluationService eval, DecisionPort decisionPort) {
        ReActExecutionLoop loop = new ReActExecutionLoop(
                new AgentDomainService(endlessToolCall()), new LifecycleHookEngine(),
                new MapTaskStateRepository(), eval);
        if (decisionPort != null) {
            loop.attachDecisionPlane(decisionPort, null, null);
        }
        return loop;
    }

    private static StubDecisionPort port(Function<DecisionRequest, DecisionResponse> responder) {
        return new StubDecisionPort(responder);
    }

    private static boolean hasRouteCount(RecordingEvaluation eval, String value) {
        return eval.metrics.stream()
                .map(m -> m.getDimensions().get(MetricDimension.DECISION))
                .filter(d -> d != null)
                .anyMatch(d -> value.equals(d.get("decision_route_counts")));
    }

    /** 决策端口桩：按脚本函数应答并计数（domain 测试禁用 Mockito）。 */
    private static final class StubDecisionPort implements DecisionPort {
        private final Function<DecisionRequest, DecisionResponse> responder;
        int calls = 0;

        StubDecisionPort(Function<DecisionRequest, DecisionResponse> responder) {
            this.responder = responder;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls++;
            return responder.apply(request);
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
