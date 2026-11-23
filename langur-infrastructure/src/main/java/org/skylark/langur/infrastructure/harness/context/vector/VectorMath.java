package org.skylark.langur.infrastructure.harness.context.vector;

/**
 * 向量数学工具（cosine 相似度）。
 */
final class VectorMath {

    private VectorMath() {
    }

    /**
     * cosine 相似度；对 null、维度不一致或零向量返回 0。
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0d;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0d || normB == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
