package org.skylark.langur.infrastructure.harness.decision;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.List;
import java.util.Locale;

/**
 * 数据驻留后端选择器（J3，DD10/P12 红线⑤）。按 {@code state} 是否命中敏感命名空间在后端间路由：
 * <ul>
 *   <li>命中敏感 + local 端点在场 → 走 {@link LocalDecisionAdapter}（自部署，数据不出网）；</li>
 *   <li>命中敏感 + 无 local 端点 → <b>fail-closed</b> 回退规则兜底，<b>绝不发往第三方 typesafe</b>；</li>
 *   <li>未命中 → 走 {@code primary}（配置的 typesafe/local 后端）。</li>
 * </ul>
 * <p>敏感判定为 {@code state} 是否包含敏感命名空间标记（大小写不敏感子串）——因 {@link DecisionRequest}
 * 不携带独立 namespace 字段，标记由运维在 {@code data-residency.sensitive-namespaces} 显式配置（如
 * {@code legal-contracts}）。本类不带 {@code @Component}，由 J3 {@code DecisionConfiguration} 在配置了
 * 敏感命名空间时装配为后端。</p>
 */
@Slf4j
public class DataResidencyDecisionPort implements DecisionPort {

    private final DecisionPort primary;
    private final DecisionPort local;
    private final DecisionPort fallback;
    private final List<String> sensitiveNamespaces;

    public DataResidencyDecisionPort(DecisionPort primary,
                                     DecisionPort local,
                                     DecisionPort fallback,
                                     List<String> sensitiveNamespaces) {
        this.primary = primary;
        this.local = local;
        this.fallback = fallback;
        this.sensitiveNamespaces = (sensitiveNamespaces == null)
                ? List.of()
                : List.copyOf(sensitiveNamespaces);
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        if (request != null && isSensitive(request.state())) {
            if (local != null) {
                return local.decide(request);
            }
            log.warn("[DECISION] sensitive state hit data-residency namespaces but no local backend "
                    + "configured, fail-closed to rule fallback (never sent to third-party)");
            return fallback.decide(request);
        }
        return primary.decide(request);
    }

    /** {@code state} 含任一敏感命名空间标记（大小写不敏感子串）即视为敏感。 */
    boolean isSensitive(String state) {
        if (sensitiveNamespaces.isEmpty() || StringUtils.isBlank(state)) {
            return false;
        }
        String lower = state.toLowerCase(Locale.ROOT);
        for (String namespace : sensitiveNamespaces) {
            if (StringUtils.isNotBlank(namespace)
                    && lower.contains(namespace.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
