package org.skylark.langur.infrastructure.harness.context;

import org.skylark.langur.domain.harness.context.ContextBuilder;
import org.skylark.langur.domain.model.message.SessionEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认上下文构建器（H15.2）：规则 elision（确定性）+ head/tail 截断（大工具输出只留首尾）。
 * <p>对应 H15.2 三段流水线的第 1、2 段——确定性、免费、可回放；第 3 段（有界 LLM 摘要）
 * 默认关闭，留作 inferential 兜底（T3 铁律：computational 优先）。</p>
 * <p>只改写进入 {@code decide} 的视图，不改动 Agent 事件日志（append-only 语义不变）。</p>
 */
@Component
public class DefaultContextBuilder implements ContextBuilder {

    private final int maxObservationChars;

    public DefaultContextBuilder(@Value("${langur.context.max-observation-chars:4000}") int maxObservationChars) {
        this.maxObservationChars = maxObservationChars;
    }

    @Override
    public List<SessionEvent> build(List<SessionEvent> sessionEvents) {
        List<SessionEvent> result = new ArrayList<>(sessionEvents.size());
        SessionEvent prev = null;
        for (SessionEvent event : sessionEvents) {
            SessionEvent trimmed = truncate(event);
            if (isAdjacentDuplicate(prev, trimmed)) {
                continue;
            }
            result.add(trimmed);
            prev = trimmed;
        }
        return result;
    }

    /** 规则 elision：相邻重复的工具结果去重（同一工具名 + 同一输出），循环反复时的常见噪声。 */
    private boolean isAdjacentDuplicate(SessionEvent prev, SessionEvent curr) {
        return prev != null
                && prev.type() == SessionEvent.Type.TOOL
                && curr.type() == SessionEvent.Type.TOOL
                && prev.name().equals(curr.name())
                && prev.content().equals(curr.content());
    }

    /** head/tail 截断：超过阈值的大观察只保留首尾 + 截断占位，全量仍在会话日志/workspace（H15.4）。 */
    private SessionEvent truncate(SessionEvent event) {
        if (event.type() != SessionEvent.Type.TOOL || event.content().length() <= maxObservationChars) {
            return event;
        }
        int head = maxObservationChars / 2;
        int tail = maxObservationChars - head;
        String content = event.content();
        String truncated = content.substring(0, head)
                + "\n…[truncated " + (content.length() - maxObservationChars) + " chars]…\n"
                + content.substring(content.length() - tail);
        return new SessionEvent(event.type(), event.sequence(), truncated, event.name(), event.toolCallId(), event.toolCalls());
    }
}
