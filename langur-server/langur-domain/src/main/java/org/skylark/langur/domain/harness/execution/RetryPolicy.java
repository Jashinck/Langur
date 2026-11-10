package org.skylark.langur.domain.harness.execution;

import lombok.Getter;

/**
 * 工具调用重试策略（§8.2 重试→降级）：次数上限 + 线性退避。
 * <p>纯领域值对象，缺省 {@link #noRetry()} 保持既有单次执行语义。</p>
 */
@Getter
public class RetryPolicy {

    private final int maxAttempts;
    private final long backoffMillis;

    public RetryPolicy(int maxAttempts, long backoffMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0L, backoffMillis);
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 100L);
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, 0L);
    }

    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }

    public void backoff(int attempt) {
        if (backoffMillis <= 0L || attempt <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
