package org.skylark.langur.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 同步对话响应：承载执行状态与最终答案。
 */
@Data
@Builder
public class ChatResponse {
    private String agentId;
    private String status;
    private String answer;
    private String lastError;
}
