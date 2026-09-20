package org.skylark.langur.infrastructure.harness.context.vector;

import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.springframework.stereotype.Component;

/**
 * C 组件 L4 - 本地词法嵌入实现（可测试默认，§12.2）。
 * <p>基于分词 + 哈希词袋 + L2 归一化的确定性嵌入：共享词元越多 cosine 越高，
 * 无需外部依赖即可支撑语义召回测试。生产可替换为 LLM 矩阵 EMBEDDING 角色（§6 M2）的真实向量。</p>
 */
@Component
public class LexicalEmbeddingPort implements EmbeddingPort {

    private static final int DIMENSIONS = 256;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f;
        }
        double norm = 0d;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0d) {
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
