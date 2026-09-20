package org.skylark.langur.domain.harness.evaluation;

/**
 * V 组件 - 评估观测服务（端口）。
 * <p>四维指标上报 + 不可篡改审计；基础设施层对接 OTel/Prometheus/MQ，
 * 埋点失败静默降级，不影响主链路（P10）。</p>
 */
public interface EvaluationService {

    void report(ExecutionMetrics metrics);

    void audit(AuditRecord record);
}
