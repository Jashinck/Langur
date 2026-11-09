package org.skylark.langur.domain.harness.context;

import org.skylark.langur.domain.model.message.SessionEvent;

import java.util.List;

/**
 * C 组件工程语义（H15.2）：把 append-only 会话事件日志裁剪为进入 LLM 推理的上下文。
 * <p>纯端口（P3），与既有 {@link ContextAssembler}（治理语义：画像/脱敏/审计）正交——
 * 本端口负责"进 prompt 之前看什么"，装配器负责"治理侧留什么"。</p>
 * <p>缺失时调用方回退原始事件（P10），行为等价 Harness 基线。</p>
 */
public interface ContextBuilder {

    /**
     * 裁剪会话事件为推理上下文：规则 elision（确定性）→ head/tail 截断（大输出只留首尾）。
     * 返回保持原序、append-only 语义不变的事件子集/改写视图。
     */
    List<SessionEvent> build(List<SessionEvent> sessionEvents);
}
