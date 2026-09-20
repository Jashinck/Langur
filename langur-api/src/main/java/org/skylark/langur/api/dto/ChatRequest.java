package org.skylark.langur.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 同步对话请求（对齐架构设计 13.1：POST /api/v1/agent/chat）。
 */
@Data
public class ChatRequest {

    @NotBlank(message = "agentId must not be blank")
    private String agentId;

    private String userMessage;
    private String userId;
    private String tenantId;
    private String sessionId;
    /** 业务域标识，驱动 SPI 路由（§11.2）；为空回退 default。 */
    private String bizCode;
    private List<MessagePartRequest> messageParts;
}
