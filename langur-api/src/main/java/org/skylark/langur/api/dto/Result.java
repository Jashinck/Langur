package org.skylark.langur.api.dto;

import lombok.Data;

/**
 * 统一返回结构（对齐架构设计 13.3：统一 Result&lt;T&gt; + 语义化 HTTP 状态码）。
 * <p>所有 v1 接入端点以该结构包装业务数据；异常由全局异常处理器映射为同构错误体。</p>
 */
@Data
public class Result<T> {

    public static final String CODE_SUCCESS = "0";

    private boolean success;
    private String code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.success = true;
        result.code = CODE_SUCCESS;
        result.message = "OK";
        result.data = data;
        return result;
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<>();
        result.success = false;
        result.code = code;
        result.message = message;
        return result;
    }
}
