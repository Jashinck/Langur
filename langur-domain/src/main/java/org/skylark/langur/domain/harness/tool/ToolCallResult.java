package org.skylark.langur.domain.harness.tool;

import lombok.Getter;

/**
 * 工具调用结果
 */
@Getter
public class ToolCallResult {

    private final boolean success;
    private final Object data;
    private final String error;
    private final long elapsedMillis;

    private ToolCallResult(boolean success, Object data, String error, long elapsedMillis) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.elapsedMillis = elapsedMillis;
    }

    public static ToolCallResult success(Object data, long elapsedMillis) {
        return new ToolCallResult(true, data, null, elapsedMillis);
    }

    public static ToolCallResult failure(String error, long elapsedMillis) {
        return new ToolCallResult(false, null, error, elapsedMillis);
    }
}
