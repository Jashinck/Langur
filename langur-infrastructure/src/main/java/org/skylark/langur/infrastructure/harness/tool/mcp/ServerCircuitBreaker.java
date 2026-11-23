package org.skylark.langur.infrastructure.harness.tool.mcp;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单 MCP 服务端熔断器（H6）- 细化多 server 隔离：某服务端连续失败达阈值即"跳闸"，
 * 冷却期内快速失败（不再反复触发慢重连），冷却后放行试探（半开），成功即复位。
 * <p>每个连接持有独立熔断器，单 server 故障仅隔离自身，不影响其余服务端正常调用。</p>
 */
public class ServerCircuitBreaker {

    private final String name;
    private final int threshold;
    private final long cooldownMillis;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long openUntil;

    public ServerCircuitBreaker(String name, int threshold, long cooldownMillis) {
        this.name = name;
        this.threshold = threshold > 0 ? threshold : 3;
        this.cooldownMillis = cooldownMillis > 0 ? cooldownMillis : 30_000L;
    }

    /** 熔断开启且仍在冷却期 → 快速失败；冷却已过 → 放行试探（半开）。 */
    public void checkOpen() {
        if (System.currentTimeMillis() < openUntil) {
            throw new McpException("MCP server circuit open (isolated), failing fast: " + name);
        }
    }

    public void recordFailure() {
        if (failures.incrementAndGet() >= threshold) {
            openUntil = System.currentTimeMillis() + cooldownMillis;
        }
    }

    public void recordSuccess() {
        failures.set(0);
        openUntil = 0L;
    }

    public boolean isOpen() {
        return System.currentTimeMillis() < openUntil;
    }
}
