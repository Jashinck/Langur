package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "t_agent_run_task", indexes = @Index(name = "idx_agent_id", columnList = "agentId"))
public class AgentRunTaskDO {

    @Id
    private String taskId;
    private String agentId;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String status;

    @Lob
    private String resultSummary;

    @Lob
    private String lastError;

    private Instant createdAt;
    private Instant updatedAt;
}
