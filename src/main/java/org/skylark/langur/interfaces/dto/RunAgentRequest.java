package org.skylark.langur.interfaces.dto;

import lombok.Data;

import java.util.List;

@Data
public class RunAgentRequest {
    private String userMessage;
    private String userId;
    private String tenantId;
    private String sessionId;
    private List<MessagePartRequest> messageParts;
}