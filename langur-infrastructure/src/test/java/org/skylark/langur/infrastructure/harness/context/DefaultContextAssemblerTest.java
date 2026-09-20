package org.skylark.langur.infrastructure.harness.context;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.AgentContext;
import org.skylark.langur.domain.harness.context.ContextSanitizer;
import org.skylark.langur.domain.harness.context.MemoryLevel;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T3 验收 - 默认上下文装配器：脱敏过滤链生效 + Token 预算治理生效（§8.1/§9.1）。
 */
class DefaultContextAssemblerTest {

    private final DefaultContextAssembler assembler =
            new DefaultContextAssembler(List.of(new KeywordMaskingContextSanitizer()));

    @Test
    void shouldMaskSensitiveContentDuringAssembly() {
        Agent agent = Agent.create(AgentConfig.defaultConfig("SanitizeAgent"));
        agent.addUserMessage("联系我 13812345678 或邮箱 foo.bar@example.com");

        AgentContext context = assembler.assemble("trace-1", "default", agent, 32_000L);

        List<String> sessionContents = context.memoriesOf(MemoryLevel.L2_SESSION).stream()
                .map(m -> m.getContent())
                .toList();
        assertFalse(sessionContents.isEmpty());
        sessionContents.forEach(content -> {
            assertFalse(content.contains("13812345678"), "手机号必须脱敏");
            assertFalse(content.contains("foo.bar@example.com"), "邮箱必须脱敏");
            assertTrue(content.contains("***"));
        });
    }

    @Test
    void shouldRejectMemoryBeyondTokenBudget() {
        Agent agent = Agent.create(AgentConfig.defaultConfig("BudgetAgent"));
        agent.addUserMessage("这是一段用于撑爆预算的较长会话内容，用于验证 Token 预算治理是否生效。");

        // 预算极小：系统提示词与会话记忆均无法写入
        AgentContext context = assembler.assemble("trace-2", "default", agent, 2L);

        assertTrue(context.getMemories().isEmpty(), "超预算记忆必须被拒绝写入");
        assertEquals(0L, context.getConsumedTokens());
    }

    @Test
    void shouldAssembleProfilesAndMemoriesWhenBudgetSufficient() {
        Agent agent = Agent.create(AgentConfig.defaultConfig("NormalAgent"));
        agent.addUserMessage("hello");

        AgentContext context = assembler.assemble("trace-3", "crm", agent, 32_000L);

        assertEquals("trace-3", context.getTraceId());
        assertEquals("crm", context.getTaskProfile().getBizCode());
        assertEquals(agent.getId().getValue(), context.getUserProfile().getUserId());
        assertEquals(1, context.memoriesOf(MemoryLevel.L3_TASK).size(), "系统提示词进入任务级记忆");
        assertEquals(1, context.memoriesOf(MemoryLevel.L2_SESSION).size(), "会话历史进入会话级记忆");
        assertTrue(context.getConsumedTokens() > 0);
    }

    @Test
    void sanitizerChainShouldHandleNullSafely() {
        ContextSanitizer sanitizer = new KeywordMaskingContextSanitizer();
        assertNull(sanitizer.sanitize(null));
        assertEquals("no sensitive data", sanitizer.sanitize("no sensitive data"));
    }
}
