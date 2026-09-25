package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
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
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1 验收（领域侧）- 执行循环优先消费 LLM 回传的真实 Token usage，缺失时降级字符估算且不抛异常，
 * 超 Token 闸门以真实 usage 触发。
 */
class TokenUsageExecutionLoopTest {

    @Test
    void shouldConsumeRealUsageWhenProviderReturnsIt() {
        RecordingEvaluation evaluation = new RecordingEvaluation();
        // prompt=10 completion=32 total=42；初始上下文 "hi" 估算 1 token → 合计 43
        LLMPort llm = finalAnswerWithUsage(LLMPort.TokenUsage.of(10, 32, 42));

        ExecutionTask task = run(llm, evaluation, TerminationGate.defaults());

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(43L, task.getConsumedTokens(), "应累加真实 usage 而非字符估算");
        Map<String, Object> model = evaluation.metrics.get(0).getDimensions().get(MetricDimension.MODEL);
        assertEquals(1, ((Number) model.get("realTokenRounds")).intValue());
        assertEquals(0, ((Number) model.get("estimatedTokenRounds")).intValue());
    }

    @Test
    void shouldFallbackToEstimateWhenUsageAbsent() {
        RecordingEvaluation evaluation = new RecordingEvaluation();
        LLMPort llm = finalAnswerWithUsage(null);

        ExecutionTask task = run(llm, evaluation, TerminationGate.defaults());

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        Map<String, Object> model = evaluation.metrics.get(0).getDimensions().get(MetricDimension.MODEL);
        assertEquals(0, ((Number) model.get("realTokenRounds")).intValue());
        assertEquals(1, ((Number) model.get("estimatedTokenRounds")).intValue(), "无 usage 时应降级估算");
        assertTrue(task.getConsumedTokens() > 0);
    }

    @Test
    void shouldTripTokenGateUsingRealUsage() {
        RecordingEvaluation evaluation = new RecordingEvaluation();
        // 工具调用使循环继续；单轮真实 usage 远超 maxTokens=10 → 闸门以真实值触发
        LLMPort llm = toolCallWithUsage(LLMPort.TokenUsage.of(500, 500, 1000));
        TerminationGate gate = TerminationGate.builder()
                .maxRounds(10).maxTokens(10L)
                .maxTimeout(java.time.Duration.ofMinutes(5)).maxCallsPerRound(5).build();

        ExecutionTask task = run(llm, evaluation, gate);

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Termination gate tripped", task.getTerminateReason());
        assertTrue(task.getConsumedTokens() >= 1000L);
    }

    private ExecutionTask run(LLMPort llmPort, EvaluationService evaluation, TerminationGate gate) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("TokenUsageAgent"));
        agent.addUserMessage("hi");
        Plan plan = new Plan(agent.getId().getValue());
        ReActExecutionLoop loop = new ReActExecutionLoop(
                new AgentDomainService(llmPort), new LifecycleHookEngine(),
                new MapTaskStateRepository(), evaluation);
        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.REACT, gate);
        return loop.execute(task, agent, plan);
    }

    private LLMPort finalAnswerWithUsage(LLMPort.TokenUsage usage) {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.finalAnswer("done", usage);
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "done";
            }
        };
    }

    private LLMPort toolCallWithUsage(LLMPort.TokenUsage usage) {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.toolCall("thinking", "missing_tool", Map.of(), usage);
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "done";
            }
        };
    }

    private static class RecordingEvaluation implements EvaluationService {
        final List<AuditRecord> audits = new ArrayList<>();
        final List<ExecutionMetrics> metrics = new ArrayList<>();

        @Override
        public void report(ExecutionMetrics executionMetrics) {
            metrics.add(executionMetrics);
        }

        @Override
        public void audit(AuditRecord record) {
            audits.add(record);
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
