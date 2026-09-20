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
 * t_tool_invocation - 工具调用流水（审计追溯 + 指标聚合）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_tool_invocation", indexes = @Index(name = "idx_invocation_task_id", columnList = "taskId"))
public class ToolInvocationDO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskId;

    private String toolId;

    private String caller;

    private String traceId;

    @Lob
    private String arguments;

    private boolean success;

    @Lob
    private String result;

    @Lob
    private String error;

    private long elapsedMillis;

    private Instant createdAt;
}
