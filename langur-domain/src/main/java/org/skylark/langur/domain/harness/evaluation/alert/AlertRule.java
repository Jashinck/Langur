package org.skylark.langur.domain.harness.evaluation.alert;

import org.skylark.langur.domain.harness.evaluation.ExecutionMetrics;

import java.util.Optional;

/**
 * 告警规则（§10.3）- 对单次执行的四维指标做阈值判定，命中则产出对应级别的 {@link Alert}。
 */
public interface AlertRule {

    /** 规则名（写入告警，便于溯源与降噪统计）。 */
    String name();

    /** 判定指标；未命中返回 {@link Optional#empty()}。 */
    Optional<Alert> evaluate(ExecutionMetrics metrics);
}
