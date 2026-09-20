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
    /** 业务域标识，驱动 SPI 路由与范式路由（§11.2）；为空时回退 default。 */
    private final String bizCode;
    private final List<MessagePartInput> messageParts;
}