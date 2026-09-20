package org.skylark.langur.domain.harness.context;

/**
 * 四级分层记忆。
 * <pre>
 * L1 瞬时（轮次销毁）→ L2 会话（摘要裁剪）→ L3 任务（快照绑定）→ L4 知识（向量检索）
 * </pre>
 */
public enum MemoryLevel {
    L1_EPHEMERAL,
    L2_SESSION,
    L3_TASK,
    L4_KNOWLEDGE
}
