package org.skylark.langur.domain.harness.decision;

import org.skylark.langur.domain.port.LLMPort;

import java.util.Map;

/**
 * 决策平面判定响应（J1）。按问题 {@code key} 映射的类型化判定集合 + Token 计量（复用 H1 {@link LLMPort.TokenUsage}）。
 * <p>纯 JDK record，零外部依赖（P1，仅依赖同域 {@code LLMPort.TokenUsage}）。{@code answers} 构造时
 * 防御性拷贝为不可变视图；{@code usage} 为空时归一为 {@link LLMPort.TokenUsage#empty()}。</p>
 */
public record DecisionResponse(Map<String, DecisionAnswer> answers,
                               LLMPort.TokenUsage usage) {

    public DecisionResponse {
        answers = (answers == null) ? Map.of() : Map.copyOf(answers);
        usage = (usage == null) ? LLMPort.TokenUsage.empty() : usage;
    }

    /** 便捷工厂：空计量响应。 */
    public static DecisionResponse of(Map<String, DecisionAnswer> answers) {
        return new DecisionResponse(answers, LLMPort.TokenUsage.empty());
    }

    /** 取指定问题的判定；不存在返回 {@code null}。 */
    public DecisionAnswer answer(String key) {
        return answers.get(key);
    }

    /** 无任何判定（后端未回传或请求无问题）。 */
    public boolean isEmpty() {
        return answers.isEmpty();
    }
}
