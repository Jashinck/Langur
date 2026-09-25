package org.skylark.langur.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.infrastructure.harness.context.vector.VectorProperties;
import org.springframework.stereotype.Component;

/**
 * 维度一致性守卫（H14.8，DD19 全局单维，补 B6）- 启动期校验 {@code langur.vector.dimension}
 * 与 {@link EmbeddingPort#dimensions()}（lexical=256 / llm=embedding.dimensions）一致。
 * <p>不一致 → <b>fail-fast</b> 明确报错（杜绝硬编码维度与实际嵌入维度冲突导致的静默召回损坏）；
 * 配置留空（{@code <=0}）则由 embedding 维度驱动，不触发校验（向后兼容，既有 memory 路径不变）。
 * ES/Milvus/pgvector 的索引/collection schema 维度均以 {@link EmbeddingPort#dimensions()} 为准。</p>
 */
@Component
public class VectorDimensionGuard {

    private static final Logger log = LoggerFactory.getLogger(VectorDimensionGuard.class);

    private final VectorProperties vectorProperties;
    private final EmbeddingPort embeddingPort;

    public VectorDimensionGuard(VectorProperties vectorProperties, EmbeddingPort embeddingPort) {
        this.vectorProperties = vectorProperties;
        this.embeddingPort = embeddingPort;
    }

    @PostConstruct
    void validate() {
        int effective = resolveEffectiveDimension(vectorProperties.getDimension(), embeddingPort.dimensions());
        log.info("[Vector] dimension guard passed: effective={} (configured={}, embedding={})",
                effective, vectorProperties.getDimension(), embeddingPort.dimensions());
    }

    /**
     * 解析生效的全局向量维度并执行一致性守卫（可离线单测）。
     *
     * @param configuredDimension {@code langur.vector.dimension}；{@code <=0} 表示未显式配置
     * @param embeddingDimension  {@link EmbeddingPort#dimensions()} 实际嵌入维度
     * @return 生效维度（未配置时取 embeddingDimension）
     * @throws IllegalStateException 配置维度与嵌入维度不一致（fail-fast）
     */
    static int resolveEffectiveDimension(int configuredDimension, int embeddingDimension) {
        if (configuredDimension <= 0) {
            return embeddingDimension;
        }
        if (configuredDimension != embeddingDimension) {
            throw new IllegalStateException(String.format(
                    "[Vector] langur.vector.dimension=%d 与 EmbeddingPort.dimensions()=%d 不一致（DD19 全局单维）："
                            + "请对齐两者，或将 langur.vector.dimension 留空由 embedding 驱动，"
                            + "否则向量库索引 schema 维度与实际嵌入冲突将导致静默召回损坏",
                    configuredDimension, embeddingDimension));
        }
        return configuredDimension;
    }
}
