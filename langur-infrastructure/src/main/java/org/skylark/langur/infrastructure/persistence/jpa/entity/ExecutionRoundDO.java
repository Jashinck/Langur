package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * t_execution_round - 执行轮次（推理数据 + 工具调用 + Token 消耗）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_execution_round", indexes = @Index(name = "idx_round_task_id", columnList = "taskId"))
public class ExecutionRoundDO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskId;

    private int round;

    @Lob
    private String thought;

    private String action;

    @Lob
    private String actionInput;

    @Lob
    private String observation;

    private long tokensUsed;

    private long elapsedMillis;

    private Instant createdAt;
}
