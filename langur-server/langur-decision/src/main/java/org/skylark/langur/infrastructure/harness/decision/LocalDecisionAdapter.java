package org.skylark.langur.infrastructure.harness.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.domain.port.DecisionPort;

/**
 * 决策平面自部署后端适配器（J3，Kev/Laya）。Kev 兼容 TypeSafe SDK——<b>仅换 base-url + 免鉴权</b>，
 * 故直接复用 {@link TypeSafeDecisionAdapter} 全部逻辑（{@code apiKey} 留空 → 不发 Authorization 头）。
 * <p>用于 {@code langur.decision.backend=local} 与数据驻留（DD10/P12⑤）：敏感 {@code state} 强制走本地端点，
 * 禁止发往第三方云。独立类型便于装配层与单测区分 local 与 typesafe 后端。</p>
 */
public class LocalDecisionAdapter extends TypeSafeDecisionAdapter {

    public LocalDecisionAdapter(String baseUrl,
                                String model,
                                long timeoutMillis,
                                ObjectMapper objectMapper,
                                DecisionPort fallback) {
        // apiKey 留空：自部署本地端点免鉴权（复用父类 REST/批量/兜底逻辑）
        super(baseUrl, model, "", timeoutMillis, objectMapper, fallback);
    }
}
