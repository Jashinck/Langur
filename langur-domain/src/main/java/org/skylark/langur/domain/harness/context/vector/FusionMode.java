package org.skylark.langur.domain.harness.context.vector;

/**
 * 混合检索融合算法（H14.2，DD17）。
 * <p>{@link #RRF}：Reciprocal Rank Fusion，基于排名、免分数归一化、跨检索器稳健（默认）；
 * {@link #WEIGHTED}：加权归一化融合，需先归一化 BM25 与 cosine 分数（尺度不同，谨慎使用）。</p>
 */
public enum FusionMode {

    RRF,
    WEIGHTED
}
