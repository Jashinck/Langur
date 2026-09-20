package org.skylark.langur.domain.harness.context.vector;

/**
 * C 组件 L4 - 向量化端口（依赖倒置，Domain 零外部依赖）。
 * <p>基础设施层可提供 LLM 矩阵 EMBEDDING 角色（§6 M2）或本地词法嵌入实现；
 * 领域层仅依赖本接口完成"写入向量化 / 检索向量化"。</p>
 */
public interface EmbeddingPort {

    /**
     * 将文本向量化。
     *
     * @param text 待嵌入文本（null/空返回零向量）
     * @return 长度为 {@link #dimensions()} 的向量
     */
    float[] embed(String text);

    /**
     * 向量维度。
     */
    int dimensions();
}
