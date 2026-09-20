package org.skylark.langur.domain.harness.context.vector;

import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * C 组件 L4 - 向量记忆记录（知识库 / 长期记忆）。
 * <p>承载 namespace（多租户隔离）、向量、原文与元数据；{@code score} 仅在检索结果中回填（cosine 相似度）。</p>
 */
@Getter
@Builder(toBuilder = true)
public class VectorRecord {

    private final String id;
    private final String namespace;
    private final float[] vector;
    private final String content;

    @Builder.Default
    private final Map<String, Object> metadata = new HashMap<>();

    /** 检索相似度得分（写入时忽略，检索时回填）。 */
    private final double score;
}
