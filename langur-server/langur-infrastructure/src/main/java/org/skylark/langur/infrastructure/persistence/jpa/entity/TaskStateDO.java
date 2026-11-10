package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * t_task_state - 任务状态主表（状态机 + 乐观锁 + 分布式锁持有者）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_task_state")
public class TaskStateDO {

    @Id
    private String taskId;

    @Column(nullable = false)
    private String status;

    /** JPA 乐观锁版本，冲突时抛 ObjectOptimisticLockingFailureException。 */
    @Version
    private Long version;

    private int currentRound;

    private String lockHolder;

    private Instant updatedAt;
}
