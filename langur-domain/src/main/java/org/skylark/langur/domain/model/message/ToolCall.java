package org.skylark.langur.domain.model.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原生工具调用（H15.1）：一次 LLM 工具调用 = 唯一 id + 工具名 + 参数。
 * <p>纯 JDK record，零外部依赖（P1）。{@code id} 即 provider 回传的 {@code tool_call_id}，
 * 用于把后续 tool 结果消息回指到本条调用；自生成调用（非 provider 原生）时 id 可为空串。</p>
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        id = (id == null) ? "" : id;
        arguments = (arguments == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static ToolCall of(String id, String name, Map<String, Object> arguments) {
        return new ToolCall(id, name, arguments);
    }

    /** 无 provider id 的单工具调用便捷工厂（自生成/测试桩）。 */
    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall("", name, arguments);
    }
}
