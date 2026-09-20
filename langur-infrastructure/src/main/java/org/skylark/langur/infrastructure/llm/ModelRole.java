package org.skylark.langur.infrastructure.llm;

/**
 * LLM 模型矩阵角色（§6）- 按职责分工组织模型，而非单一模型承担所有职能。
 */
public enum ModelRole {
    /** M1 轻量路由：意图识别、任务分流、低成本预处理。 */
    ROUTING,
    /** M2 向量化：文本 Embedding、知识库入库（供 T14 向量记忆调用）。 */
    EMBEDDING,
    /** M3 重排：向量检索结果重排序去噪。 */
    RERANK,
    /** M4 行动：Function Call、工具参数生成。 */
    ACTION,
    /** M5 推理：规划、自检质检、复杂推理。 */
    REASONING,
    /** M6 长上下文：长文档处理、日志审计。 */
    LONG_CONTEXT
}
