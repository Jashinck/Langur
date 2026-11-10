package org.skylark.langur.domain.harness.rsi;

import java.util.List;

/**
 * 回放指标（R0）。一次重放产出的 V 维度指标剖面——成功率、轮次、Token、延迟、拦截次数与决策分流路径。
 * <p>纯 JDK record，零外部依赖（P1）。{@code routePath} 按轮次顺序记录每个判定的分流名（小写），
 * 用于断言"原策略重放复现原分流路径"。列表构造时防御性拷贝为不可变。</p>
 *
 * @param success        重放后任务是否成功（提前 TERMINATE 截断未完成 → false）
 * @param rounds         实际执行的轮次数（SKIP/TERMINATE 会减少）
 * @param tokens         累计 Token（复用 H1 真实计量）
 * @param latencyMillis  累计延迟（执行轮次 + 判定往返）
 * @param interceptions  保守路径次数（APPROVE/FAIL_CLOSED，人审/规则兜底的摩擦度量）
 * @param routePath      按轮次的分流路径（如 {@code [run, skip, run]}）
 */
public record ReplayMetrics(boolean success,
                            int rounds,
                            long tokens,
                            long latencyMillis,
                            int interceptions,
                            List<String> routePath) {

    public ReplayMetrics {
        routePath = (routePath == null) ? List.of() : List.copyOf(routePath);
    }

    /** 便捷工厂。 */
    public static ReplayMetrics of(boolean success, int rounds, long tokens,
                                   long latencyMillis, int interceptions, List<String> routePath) {
        return new ReplayMetrics(success, rounds, tokens, latencyMillis, interceptions, routePath);
    }
}
