package org.skylark.langur.application.command;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class RunAgentCommand {
    private final String agentId;
    private final String userMessage;
    private final String userId;
    private final String tenantId;
    private final String sessionId;
    private final List<MessagePartInput> messageParts;
}