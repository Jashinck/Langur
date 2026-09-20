package org.skylark.langur.domain.harness.context;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 记忆条目 - 绑定记忆级别与 Token 估算
 */
@Getter
@Builder
public class MemoryEntry {

    private final MemoryLevel level;
    private final String content;
    private final long tokenEstimate;
    private final Instant createdAt;

    public static MemoryEntry of(MemoryLevel level, String content, long tokenEstimate) {
        return MemoryEntry.builder()
                .level(level)
                .content(content)
                .tokenEstimate(tokenEstimate)
                .createdAt(Instant.now())
                .build();
    }
}
