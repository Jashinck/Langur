package org.skylark.langur.domain.harness.decision;

/**
 * 决策平面判定类型（J1）——对齐 Jev / System One 的三类判定：{@code choice/noul/score}。
 * <p>纯 JDK 值对象，零外部依赖（P1）。判定类型决定 {@link DecisionAnswer} 的取值语义：
 * CHOICE 读枚举 + 分布，PROBABILITY/SCORE 读连续值。</p>
 */
public enum DecisionType {

    /** 枚举选择（Jev {@code choice}）：从候选中择一，附带完整分布。 */
    CHOICE("choice"),

    /** 概率判定（Jev {@code noul}）：0..1 的可能性，用于完成/重复/命中等布尔式判定。 */
    PROBABILITY("noul"),

    /** 连续评分（Jev {@code score}）：0..1 分档，用于风险分级/产物验收。 */
    SCORE("score");

    private final String wireName;

    DecisionType(String wireName) {
        this.wireName = wireName;
    }

    /** Jev 请求/响应中的字段名（choice/noul/score）。 */
    public String wireName() {
        return wireName;
    }

    /** 由 Jev wire 名解析类型；{@code null} 或未知返回 {@code null}。 */
    public static DecisionType fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (DecisionType type : values()) {
            if (type.wireName.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
