package org.skylark.langur.infrastructure.harness.decision;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.harness.evaluation.MicrometerEvaluationService;
import org.skylark.langur.infrastructure.harness.evaluation.audit.AuditSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J2 验收 - {@link RecordingDecisionPort} 离线确定性单测（纯 JUnit5 + SimpleMeterRegistry 桩）。
 * <p>覆盖：record=true 录制通道收到 request/response、record=false 不录制、决策维度指标
 * （latency/confidence/fallback_rate/cost）经 H5 落 MeterRegistry 可读、降级时 fallback_rate=1、
 * 审计记录含判定 checksum 与 confidence 明细、判定结果原样透传、观测缺件时不抛出（P10）。</p>
 */
class RecordingDecisionPortTest {

    private static DecisionRequest request() {
        return DecisionRequest.of("合同正文", "jev-1.13.0", List.of(
                DecisionQuestion.choice("route", "选择范式", Map.of("react", "简单", "plan", "复杂"))));
    }

    private static DecisionResponse confidentResponse() {
        return new DecisionResponse(Map.of(
                "route", DecisionAnswer.ofChoice("react", 0.9d, Map.of("react", 0.9d, "plan", 0.1d))),
                LLMPort.TokenUsage.of(120L, 0L, 120L));
    }

    private static DecisionResponse degradedResponse() {
        return new DecisionResponse(Map.of(
                "route", new DecisionAnswer(DecisionType.CHOICE, "react", 0d, 0d, Map.of())),
                LLMPort.TokenUsage.empty());
    }

    /** 固定返回预定响应的后端桩。 */
    private static final class FixedDelegate implements DecisionPort {
        private final DecisionResponse response;

        FixedDelegate(DecisionResponse response) {
            this.response = response;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            return response;
        }
    }

    /** 捕获型录制通道桩（"桩快照仓储"）。 */
    private static final class CapturingRecorder implements DecisionTrajectoryRecorder {
        final List<StateSnapshot> snapshots = new ArrayList<>();

        @Override
        public void record(StateSnapshot snapshot) {
            snapshots.add(snapshot);
        }
    }

    /** 捕获型审计出口桩。 */
    private static final class CapturingSink implements AuditSink {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void archive(AuditRecord record) {
            records.add(record);
        }
    }

    @Test
    void shouldRecordTrajectorySnapshotWhenRecordEnabled() {
        CapturingRecorder recorder = new CapturingRecorder();
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(confidentResponse()), null, recorder, true);

        port.decide(request());

        assertEquals(1, recorder.snapshots.size(), "record=true 应落一条轨迹快照");
        StateSnapshot snapshot = recorder.snapshots.get(0);
        assertNotNull(snapshot.getPayload().get("request"), "快照含 request（R0 前向兼容）");
        assertNotNull(snapshot.getPayload().get("response"), "快照含 response");
        assertNotNull(snapshot.getPayload().get("latencyMillis"));
    }

    @Test
    void shouldNotRecordWhenRecordDisabled() {
        CapturingRecorder recorder = new CapturingRecorder();
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(confidentResponse()), null, recorder, false);

        port.decide(request());

        assertTrue(recorder.snapshots.isEmpty(), "record=false 不录制");
    }

    @Test
    void shouldReportDecisionMetricsToMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerEvaluationService evaluation =
                new MicrometerEvaluationService(registry, null, List.of());
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(confidentResponse()), evaluation, null, true);

        port.decide(request());

        DistributionSummary latency = registry.find("langur.harness.decision_latency").summary();
        assertNotNull(latency);
        assertEquals(1, latency.count());
        assertEquals("DECISION", latency.getId().getTag("dimension"));

        DistributionSummary confidence = registry.find("langur.harness.decision_confidence").summary();
        assertNotNull(confidence);
        assertEquals(0.9d, confidence.totalAmount(), 1e-9);

        DistributionSummary fallback = registry.find("langur.harness.decision_fallback_rate").summary();
        assertNotNull(fallback);
        assertEquals(0.0d, fallback.totalAmount(), 1e-9, "高置信非降级 → fallback_rate=0");

        DistributionSummary cost = registry.find("langur.harness.decision_cost").summary();
        assertNotNull(cost);
        assertEquals(120d, cost.totalAmount(), 1e-9, "decision_cost=输入 token");
    }

    @Test
    void shouldFlagFallbackRateWhenResponseDegraded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerEvaluationService evaluation =
                new MicrometerEvaluationService(registry, null, List.of());
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(degradedResponse()), evaluation, null, true);

        port.decide(request());

        DistributionSummary fallback = registry.find("langur.harness.decision_fallback_rate").summary();
        assertNotNull(fallback);
        assertEquals(1.0d, fallback.totalAmount(), 1e-9, "confidence 全 0 → 判定降级 → fallback_rate=1");
    }

    @Test
    void shouldAuditDecisionWithChecksumAndConfidenceDetail() {
        CapturingSink sink = new CapturingSink();
        MicrometerEvaluationService evaluation =
                new MicrometerEvaluationService(new SimpleMeterRegistry(), null, List.of(sink));
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(confidentResponse()), evaluation, null, true);

        port.decide(request());

        assertEquals(1, sink.records.size());
        AuditRecord record = sink.records.get(0);
        assertEquals("DECISION", record.getAction());
        assertNotNull(record.getChecksum());
        assertEquals(64, record.getChecksum().length(), "SHA-256 十六进制校验和");
        assertTrue(record.getDetail().contains("conf=0.9"), "审计明细含 confidence");
        assertTrue(record.getDetail().contains("react=0.9"), "审计明细含 distribution");
    }

    @Test
    void shouldPassThroughDelegateResponseUnchanged() {
        DecisionResponse delegateResponse = confidentResponse();
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(delegateResponse), null, null, true);

        DecisionResponse result = port.decide(request());

        assertSame(delegateResponse, result, "录制装饰器不得改写判定结果");
    }

    @Test
    void shouldNotThrowWhenObservabilityAbsent() {
        RecordingDecisionPort port = new RecordingDecisionPort(
                new FixedDelegate(confidentResponse()), null, null, true);

        DecisionResponse result = port.decide(request());

        assertNotNull(result);
        assertEquals("react", result.answer("route").choice());
    }
}
