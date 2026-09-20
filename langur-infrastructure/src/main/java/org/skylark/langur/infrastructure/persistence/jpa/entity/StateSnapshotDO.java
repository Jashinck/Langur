package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * t_state_snapshot - 执行快照（断点续跑 + 回滚）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_state_snapshot", indexes = @Index(name = "idx_snapshot_task_id", columnList = "taskId"))
public class StateSnapshotDO {

    @Id
    private String snapshotId;

    private String taskId;

    private int round;

    @Lob
    private String payload;

    private Instant createdAt;
}
