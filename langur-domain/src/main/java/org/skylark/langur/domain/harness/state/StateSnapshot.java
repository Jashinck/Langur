package org.skylark.langur.domain.harness.state;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 执行快照 - 支持断点续跑与回滚到任意步骤
 */
@Getter
@Builder
public class StateSnapshot {

    private final String snapshotId;
    private final String taskId;
    private final int round;
    private final Map<String, Object> payload;
    private final Instant createdAt;

    public static StateSnapshot of(String taskId, int round, Map<String, Object> payload) {
        return StateSnapshot.builder()
                .snapshotId(UUID.randomUUID().toString())
                .taskId(taskId)
                .round(round)
                .payload(payload)
                .createdAt(Instant.now())
                .build();
    }
}
