package org.skylark.langur.domain.harness.execution;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

/**
 * 终止闸门 - 四维硬约束：maxRounds / maxTokens / maxTimeout / maxCallsPerRound。
 * <p>用于杜绝资源溢出，是 E 组件的核心保护机制。</p>
 */
@Getter
@Builder
public class TerminationGate {

    private final int maxRounds;
    private final long maxTokens;
    private final Duration maxTimeout;
    private final int maxCallsPerRound;

    public static TerminationGate defaults() {
        return TerminationGate.builder()
                .maxRounds(10)
                .maxTokens(32_000L)
                .maxTimeout(Duration.ofMinutes(5))
                .maxCallsPerRound(5)
                .build();
    }

    public boolean exceedsRounds(int rounds) {
        return rounds >= maxRounds;
    }

    public boolean exceedsTokens(long tokens) {
        return tokens >= maxTokens;
    }

    public boolean exceedsCallsPerRound(int calls) {
        return calls >= maxCallsPerRound;
    }

    public boolean exceedsTimeout(Instant startedAt) {
        return maxTimeout != null && startedAt != null
                && Duration.between(startedAt, Instant.now()).compareTo(maxTimeout) > 0;
    }
}
