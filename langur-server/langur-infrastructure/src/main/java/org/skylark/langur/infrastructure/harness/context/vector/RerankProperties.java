package org.skylark.langur.infrastructure.harness.context.vector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * C 组件 L4 - 重排配置（H2，M3）。
 * <p>{@code enabled=false}（默认）时不装配 {@link org.skylark.langur.domain.harness.context.vector.RerankPort}，
 * 召回结果沿用向量库 cosine 顺序。开启后按 {@code alpha} 混合语义 cosine 与词面重叠打分重排。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "langur.rerank")
public class RerankProperties {

    /** 是否启用重排（默认关闭）。 */
    private boolean enabled = false;

    /** 语义 cosine 权重（0..1）；剩余权重给词面重叠，构成混合打分。 */
    private double alpha = 0.7d;

    /** 重排后保留上限；{@code <=0} 表示沿用召回候选规模。 */
    private int topK = 0;
}
