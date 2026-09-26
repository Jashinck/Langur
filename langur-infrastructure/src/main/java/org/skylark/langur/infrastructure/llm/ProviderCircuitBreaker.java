package org.skylark.langur.infrastructure.llm;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provider 级熔断器（H13.7）——LLM provider 的健康隔离：连续失败达阈值即"跳闸"，
 * 冷却期内 {@link #isOpen()} 为 true（调用方快速降级到下一个模型，不再反复触发慢超时），
 * 冷却后放行试探（半开），成功即复位、失败重新跳闸。
 * <p>与 H6 {@code ServerCircuitBreaker} 同构但通用化（不抛 MCP 异常，由调用方轮询 {@link #isOpen()}），
 * 每 provider 独立隔离，单家故障不拖垮降级链。纯 JDK 零外部依赖，可离线确定性单测。</p>
 */
public class ProviderCircuitBreaker {

    private final String name;
    private final int threshold;
    private final long cooldownMillis;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long openUntil;

    public ProviderCircuitBreaker(String name, int threshold, long cooldownMillis) {
        this.name = name == null ? "unknown" : name;
        this.threshold = threshold > 0 ? threshold : 3;
        this.cooldownMillis = cooldownMillis > 0 ? cooldownMillis : 30_000L;
    }

    /** 是否处于跳闸冷却期（调用方据此快速降级，不发起实际调用）。 */
    public boolean isOpen() {
        return System.currentTimeMillis() < openUntil;
    }

    /** 记录一次失败：连续失败达阈值即跳闸进入冷却。 */
    public void recordFailure() {
        if (failures.incrementAndGet() >= threshold) {
            openUntil = System.currentTimeMillis() + cooldownMillis;
        }
    }

    /** 记录一次成功：复位失败计数并立即闭合。 */
    public void recordSuccess() {
        failures.set(0);
        openUntil = 0L;
    }

    public String name() {
        return name;
    }
}
