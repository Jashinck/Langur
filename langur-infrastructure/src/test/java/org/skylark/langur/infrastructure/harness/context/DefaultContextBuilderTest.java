package org.skylark.langur.infrastructure.harness.context;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.message.SessionEvent;
import org.skylark.langur.domain.model.message.ToolCall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H15.2 验收：规则 elision（相邻重复工具结果去重）+ head/tail 截断（大观察只留首尾）。
 * 纯单元测试，直接构造 builder（无 Spring 上下文）。
 */
class DefaultContextBuilderTest {

    private final DefaultContextBuilder builder = new DefaultContextBuilder(100);

    @Test
    void shouldTruncateLargeToolObservationToHeadAndTail() {
        String big = "A".repeat(500);
        List<SessionEvent> events = List.of(
                SessionEvent.user(0, "hi"),
                SessionEvent.tool(1, "c1", "grep", big));

        List<SessionEvent> built = builder.build(events);

        assertEquals(2, built.size());
        String content = built.get(1).content();
        assertTrue(content.length() < 200, "截断后应显著短于原文");
        assertTrue(content.contains("[truncated"), "应带截断占位");
        assertTrue(content.startsWith("A"), "保留头部");
        assertTrue(content.endsWith("A"), "保留尾部");
    }

    @Test
    void shouldDedupeAdjacentDuplicateToolResults() {
        List<SessionEvent> events = List.of(
                SessionEvent.tool(0, "c1", "grep", "same"),
                SessionEvent.tool(1, "c2", "grep", "same"),
                SessionEvent.tool(2, "c3", "grep", "different"));

        List<SessionEvent> built = builder.build(events);

        assertEquals(2, built.size());
        assertEquals("c3", built.get(1).toolCallId());
    }

    @Test
    void shouldKeepUserAndAssistantEventsUntouched() {
        List<SessionEvent> events = List.of(
                SessionEvent.user(0, "hello"),
                SessionEvent.assistantToolCalls(1, "think", List.of(ToolCall.of("id1", "echo", java.util.Map.of()))),
                SessionEvent.assistant(2, "final"));

        assertEquals(events, builder.build(events));
    }

    @Test
    void shouldPassthroughSmallObservations() {
        List<SessionEvent> events = List.of(SessionEvent.tool(0, "c1", "echo", "short result"));
        assertEquals(events, builder.build(events));
    }
}
