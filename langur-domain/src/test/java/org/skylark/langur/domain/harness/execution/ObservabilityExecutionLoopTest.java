package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12 验收（领域侧）- 六类 Span 埋点（上下文/推理/快照/输出）+ 四维指标采集 + 拦截不可篡改审计。
 */
class ObservabilityExecutionLoopTest {

    @Test
    void shouldEmitSpanChainAndFourDimensionMetricsOnSuccessfulRun() {
        RecordingTracer tracer = new RecordingTracer();
        RecordingEvaluation evaluation = new RecordingEvaluation();

        ExecutionTask task = run(finalAnswerLlm(), new LifecycleHookEngine(), tracer, evaluation);

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertTrue(tracer.spans.contains(SpanType.CONTEXT), "应产生上下文构建 Span");
        assertTrue(tracer.spans.contains(SpanType.INFERENCE), "应产生 LLM 推理 Span");
        assertTrue(tracer.spans.contains(SpanType.SNAPSHOT), "应产生状态写入 Span");
        assertTrue(tracer.spans.contains(SpanType.OUTPUT), "应产生响应输出 Span");

        assertEquals(1, evaluation.metrics.size());
        Map<MetricDimension, Map<String, Object>> dimensions = evaluation.metrics.get(0).getDimensions();
        assertTrue(dimensions.containsKey(MetricDimension.SCHEDULING));
        assertTrue(dimensions.containsKey(MetricDimension.MODEL));
        assertTrue(dimensions.containsKey(MetricDimension.TOOL));
        assertTrue(dimensions.containsKey(MetricDimension.SECURITY));
    }

    @Test
    void shouldAuditInterceptionWithChecksumWhenOutputAborted() {
        RecordingTracer tracer = new RecordingTracer();
        RecordingEvaluation evaluation = new RecordingEvaluation();

        LifecycleHookEngine engine = new LifecycleHookEngine();
        engine.register(new LifecycleHook() {
            @Override
            public HookPoint point() {
                return HookPoint.BEFORE_OUTPUT;
            }

            @Override
            public HookResult execute(HookContext context) {
                return HookResult.abort("content policy violation");
            }
        });

        ExecutionTask task = run(finalAnswerLlm(), engine, tracer, evaluation);

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        Optional<AuditRecord> outputRejected = evaluation.audits.stream()
                .filter(record -> "OUTPUT_REJECTED".equals(record.getAction()))
                .findFirst();
        assertTrue(outputRejected.isPresent(), "输出拦截应产生审计记录");
        assertFalse(outputRejected.get().getChecksum().isBlank(), "审计记录应含 checksum");
        assertEquals(64, outputRejected.get().getChecksum().length());

        Object interceptions = evaluation.metrics.get(0)
                .getDimensions().get(MetricDimension.SECURITY).get("interceptions");
        assertEquals(1, ((Number) interceptions).intValue());
    }

    private ExecutionTask run(LLMPort llmPort, LifecycleHookEngine engine,
                              ExecutionTracer tracer, EvaluationService evaluation) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("ObservabilityAgent"));
        agent.addUserMessage("hi");
        Plan plan = new Plan(agent.getId().getValue());
        ReActExecutionLoop loop = new ReActExecutionLoop(
                new AgentDomainService(llmPort), engine, new MapTaskStateRepository(), evaluation);
        loop.attachTracer(tracer);
        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.REACT, TerminationGate.defaults());
        return loop.execute(task, agent, plan);
    }

    private LLMPort finalAnswerLlm() {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.finalAnswer("done");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "done";
            }
        };
    }

    private static class RecordingTracer implements ExecutionTracer {
        final List<SpanType> spans = new ArrayList<>();

        @Override
        public ExecutionSpan startSpan(SpanType type, String operationName) {
            spans.add(type);
            return new RecordingSpan();
        }

        @Override
        public String currentTraceId() {
            return "trace-test";
        }
    }

    private static class RecordingSpan implements ExecutionSpan {
        @Override
        public ExecutionSpan setAttribute(String key, String value) {
            return this;
        }

        @Override
        public ExecutionSpan setAttribute(String key, long value) {
            return this;
        }

        @Override
        public ExecutionSpan setAttribute(String key, boolean value) {
            return this;
        }

        @Override
        public void recordError(Throwable throwable) {
            // no-op
        }

        @Override
        public void end() {
            // no-op
        }
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
