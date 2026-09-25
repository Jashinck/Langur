package org.skylark.langur.infrastructure.harness.rsi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RSI 配置（R0，{@code langur.rsi.*}，P9）。
 * <p>总开关 {@link #enabled} 默认 {@code false}——未开启时 {@code RsiConfiguration} 不装配，不产出任何 RSI Bean，
 * 系统行为与既有版本完全一致（P10/P11 红线：RSI 暂停态）。开启后仅装配<b>离线回放验证底座</b>
 * （{@code ReplayEngine} + {@code TrajectoryRepository}）；RSI 自改进产物默认是候选提案，回放通过<b>不等于生效</b>，
 * 生效须经 R-G 灰度 + 高危人审（P11）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.rsi")
public class RsiProperties {

    /** 总开关，默认关（P10/P11）。开启后装配 R0 回放底座；关闭时不产出 RSI Bean，行为等价既有版本。 */
    private boolean enabled = false;
}
