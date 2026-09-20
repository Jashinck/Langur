package org.skylark.langur.domain.model.execution;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class AgentRunTask {

    private final String taskId;
    private final String agentId;
    private final String userId;
    private final String tenantId;
    private final String sessionId;
    private final String userMessage;
    private final Instant createdAt;
    private Instant updatedAt;
    private RunTaskStatus status;
    private String resultSummary;
    private String lastError;

    private AgentRunTask(String taskId, String agentId, String userId, String tenantId,
                         String sessionId, String userMessage, Instant createdAt, Instant updatedAt,
                         RunTaskStatus status, String resultSummary, String lastError) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resultSummary = resultSummary;
        this.lastError = lastError;
    }

    public static AgentRunTask create(String agentId, String userId, String tenantId, String sessionId) {
        return create(agentId, userId, tenantId, sessionId, null);
    }

    public static AgentRunTask create(String agentId, String userId, String tenantId,
                                      String sessionId, String userMessage) {
        Instant now = Instant.now();
        return new AgentRunTask(UUID.randomUUID().toString(), agentId, userId, tenantId, sessionId,
                userMessage, now, now, RunTaskStatus.PENDING, null, null);
    }

    public static AgentRunTask restore(String taskId, String agentId, String userId, String tenantId,
                                       String sessionId, String userMessage, Instant createdAt, Instant updatedAt,
                                       RunTaskStatus status, String resultSummary, String lastError) {
        return new AgentRunTask(taskId, agentId, userId, tenantId, sessionId,
                userMessage, createdAt, updatedAt, status, resultSummary, lastError);
    }

    public void markRunning() {
        this.status = RunTaskStatus.RUNNING;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void markCompleted(String resultSummary) {
        this.status = RunTaskStatus.COMPLETED;
        this.resultSummary = resultSummary;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = RunTaskStatus.FAILED;
        this.lastError = error;
        this.updatedAt = Instant.now();
    }

    public void markCancelled(String reason) {
        this.status = RunTaskStatus.CANCELLED;
        this.lastError = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * 任务控制面：仅终态失败/已取消的任务允许重试（重试将基于原请求派生新任务）
     */
    public boolean isRetryable() {
        return status == RunTaskStatus.FAILED || status == RunTaskStatus.CANCELLED;
    }
}
