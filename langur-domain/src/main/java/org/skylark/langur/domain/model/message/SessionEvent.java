package org.skylark.langur.domain.model.message;

import java.util.List;

/**
 * 会话事件日志条目（H15.1）：把会话建模为 append-only 事件日志的最小单元。
 * <p>纯 JDK record，零外部依赖（P1）。三种类型对应 provider 原生消息：
 * {@code USER}（用户消息）、{@code ASSISTANT}（助手消息，可携带原生 tool_calls）、
 * {@code TOOL}（工具结果，以 {@code toolCallId} 回指助手 tool_calls）。</p>
 * <p>事件日志只增不改（append-only）：这是 R0 回放、R2 蒸馏、Client 轨迹时间线的共同地基；
 * {@code sequence} 为单调递增序号，供快照事件游标（H15.3）定位。</p>
 */
public record SessionEvent(
        Type type,
        long sequence,
        String content,
        String name,
        String toolCallId,
        List<ToolCall> toolCalls) {

    public enum Type { USER, ASSISTANT, TOOL }

    public SessionEvent {
        content = (content == null) ? "" : content;
        name = (name == null) ? "" : name;
        toolCallId = (toolCallId == null) ? "" : toolCallId;
        toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
    }

    public static SessionEvent user(long sequence, String content) {
        return new SessionEvent(Type.USER, sequence, content, "", "", List.of());
    }

    public static SessionEvent assistant(long sequence, String content) {
        return new SessionEvent(Type.ASSISTANT, sequence, content, "", "", List.of());
    }

    public static SessionEvent assistantToolCalls(long sequence, String thought, List<ToolCall> toolCalls) {
        return new SessionEvent(Type.ASSISTANT, sequence, thought, "", "", toolCalls);
    }

    public static SessionEvent tool(long sequence, String toolCallId, String name, String content) {
        return new SessionEvent(Type.TOOL, sequence, content, name, toolCallId, List.of());
    }
}
