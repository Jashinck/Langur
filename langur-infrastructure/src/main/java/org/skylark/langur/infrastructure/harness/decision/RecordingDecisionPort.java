package org.skylark.langur.infrastructure.harness.decision;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.Checksums;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;
import org.skylark.langur.domain.harness.evaluation.MetricDimension;
import org.skylark.langur.domain.harness.state.StateSnapshot;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.Map;
import java.util.TreeMap;

/**
 * 决策平面录制装饰器（J2）。包裹后端 {@link DecisionPort}，在 {@code record=true} 时把每次判定的
 * request/response 落轨迹快照（{@link DecisionTrajectoryRecorder}，R0 前向兼容，C2/DD12），
 * 并经 H5 {@link EvaluationService} 上报<b>决策维度</b>指标（{@code decision_latency/confidence/
 * fallback_rate/cost}，{@code decision_route_counts} 由 J3 {@code ThresholdRouter} 补）与不可篡改审计
 * （判定含 confidence/distribution 写入审计链，checksum 溯源"为何走了这条分支"）。
 * <p>录制/指标/审计全部失败静默降级（P10），绝不反噬主链路；判定结果原样透传。本类不带 {@code @Component}，
 * 由 J3 {@code DecisionConfiguration} 装配为装饰链最外层。</p>
 * <p>{@code fallback_rate} 依据 {@link RuleFallbackDecisionAdapter} 的 confidence 恒 0 契约推断降级
 * （非空且全部 confidence==0 视为规则兜底），用于观测后端健康度。</p>
 */
@Slf4j
public class RecordingDecisionPort implements DecisionPort {

    private static final String DECISION_TRACE = "decision-plane";
    private static final String AUDIT_ACTION = "DECISION";

    private final DecisionPort delegate;
    private final EvaluationService evaluationService;
    private final DecisionTrajectoryRecorder recorder;
    private final boolean record;

    public RecordingDecisionPort(DecisionPort delegate,
                                 EvaluationService evaluationService,
                                 DecisionTrajectoryRecorder recorder,
                                 boolean record) {
        this.delegate = delegate;
        this.evaluationService = evaluationService;
        this.recorder = recorder;
        this.record = record;
    }

    /** 被包裹的下层端口（供 J3 装饰链装配顺序校验）。 */
    public DecisionPort getDelegate() {
        return delegate;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        long startNanos = System.nanoTime();
        DecisionResponse response = delegate.decide(request);
        long latencyMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        if (response == null) {
            response = DecisionResponse.of(Map.of());
        }
        recordTrajectory(request, response, latencyMillis);
        reportMetrics(request, response, latencyMillis);
        return response;
    }

    /** record=true 且通道在场时落轨迹快照（R0 前向兼容）；失败静默降级（P10）。 */
    private void recordTrajectory(DecisionRequest request, DecisionResponse response, long latencyMillis) {
        if (!record || recorder == null) {
            return;
        }
        try {
            StateSnapshot snapshot = StateSnapshot.of(DECISION_TRACE, 0, Map.of(
                    "request", request == null ? "" : request,
                    "response", response,
                    "latencyMillis", latencyMillis));
            recorder.record(snapshot);
        } catch (RuntimeException e) {
            log.debug("[DECISION-RECORD] trajectory record failed, silently dropped", e);
        }
    }

    /** 决策维度指标 + 审计（含 checksum）；失败静默降级（P10）。 */
    private void reportMetrics(DecisionRequest request, DecisionResponse response, long latencyMillis) {
        if (evaluationService == null) {
            return;
        }
        try {
            ExecutionMetrics metrics = ExecutionMetrics.of(DECISION_TRACE);
            metrics.record(MetricDimension.DECISION, "decision_latency", latencyMillis);
            metrics.record(MetricDimension.DECISION, "decision_confidence", averageConfidence(response));
            metrics.record(MetricDimension.DECISION, "decision_fallback_rate", isDegraded(response) ? 1.0d : 0.0d);
            metrics.record(MetricDimension.DECISION, "decision_cost", response.usage().getPromptTokens());
            evaluationService.report(metrics);

            String detail = renderDetail(response);
            String checksum = Checksums.sha256(DECISION_TRACE, AUDIT_ACTION,
                    request == null ? "" : request.state(), detail);
            evaluationService.audit(AuditRecord.of(DECISION_TRACE, "decision-plane", AUDIT_ACTION, detail, checksum));
        } catch (RuntimeException e) {
            log.debug("[DECISION] metric/audit report failed, silently dropped", e);
        }
    }

    /** 全部答案的平均置信度；空响应为 0。 */
    private double averageConfidence(DecisionResponse response) {
        if (response.answers().isEmpty()) {
            return 0d;
        }
        double sum = 0d;
        for (DecisionAnswer answer : response.answers().values()) {
            sum += answer.confidence();
        }
        return sum / response.answers().size();
    }

    /** 降级判定：非空且全部 confidence==0（{@link RuleFallbackDecisionAdapter} 契约）。 */
    private boolean isDegraded(DecisionResponse response) {
        if (response.isEmpty()) {
            return false;
        }
        for (DecisionAnswer answer : response.answers().values()) {
            if (answer.confidence() != 0d) {
                return false;
            }
        }
        return true;
    }

    /** 确定性渲染判定明细（含 choice/value/confidence/distribution），供审计 detail 与 checksum。 */
    private String renderDetail(DecisionResponse response) {
        StringBuilder sb = new StringBuilder();
        // TreeMap 保证 key 有序 → checksum 稳定
        Map<String, DecisionAnswer> ordered = new TreeMap<>(response.answers());
        ordered.forEach((key, answer) -> {
            sb.append(key).append('=').append(answer.type().wireName()).append(':');
            if (answer.choice() != null) {
                sb.append(answer.choice());
            } else {
                sb.append(answer.value());
            }
            sb.append("@conf=").append(answer.confidence());
            if (!answer.distribution().isEmpty()) {
                sb.append("{");
                new TreeMap<>(answer.distribution())
                        .forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
                sb.append('}');
            }
            sb.append(';');
        });
        sb.append("tokens=").append(response.usage().getTotalTokens());
        return sb.toString();
    }
}
