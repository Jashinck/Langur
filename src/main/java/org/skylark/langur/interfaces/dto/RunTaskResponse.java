package org.skylark.langur.interfaces.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RunTaskResponse {
    private String taskId;
    private String agentId;
    private String status;
    private String resultSummary;
    private String lastError;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String createdAt;
    private String updatedAt;
}
