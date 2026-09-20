package org.skylark.langur.domain.harness.tool;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 工具调用请求 - 四层校验链的输入
 */
@Getter
@Builder
public class ToolCallRequest {

    private final String toolId;
    private final String caller;
    private final String traceId;
    private final Map<String, Object> arguments;
}
