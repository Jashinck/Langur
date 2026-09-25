package org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON-RPC 2.0 响应体。result 与 error 二选一；反序列化时忽略未知字段以兼容不同 MCP 服务端扩展。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonRpcResponse(String jsonrpc, String id, Object result, JsonRpcError error) {

    /** 派生判定，非可序列化属性——否则会与 record 组件 {@code error} 冲突（被当作 boolean 属性）。 */
    @JsonIgnore
    public boolean isError() {
        return error != null;
    }
}
