package org.skylark.langur.infrastructure.harness.context;

import lombok.RequiredArgsConstructor;
import org.skylark.langur.domain.harness.context.AgentContext;
import org.skylark.langur.domain.harness.context.ContextAssembler;
import org.skylark.langur.domain.harness.context.ContextSanitizer;
import org.skylark.langur.domain.harness.context.MemoryEntry;
import org.skylark.langur.domain.harness.context.MemoryLevel;
import org.skylark.langur.domain.harness.context.TaskProfile;
import org.skylark.langur.domain.harness.context.UserProfile;
import org.skylark.langur.domain.model.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * C 组件 - 默认上下文装配实现（对齐架构设计 §8.1）。
 * <p>构建双画像 + 装配分层记忆（系统提示词 → L3 任务；会话历史 → L2 会话），
 * 记忆内容先经脱敏过滤链，再受 Token 预算治理（超预算写入被拒绝）。</p>
 */
@Component
@RequiredArgsConstructor
public class DefaultContextAssembler implements ContextAssembler {

    private final List<ContextSanitizer> sanitizers;

    @Override
    public AgentContext assemble(String traceId, String bizCode, Agent agent, long tokenBudget) {
        UserProfile userProfile = UserProfile.builder()
                .userId(agent.getId().getValue())
                .permissions(List.of())
                .maskingSwitches(Map.of())
                .preferences(Map.of())
                .build();
        TaskProfile taskProfile = TaskProfile.builder()
                .bizCode(bizCode)
                .riskLevel("LOW")
                .allowedToolIds(agent.getRegisteredToolNames())
                .promptTemplate(agent.getConfig().getSystemPrompt())
                .build();

        AgentContext context = AgentContext.create(traceId, userProfile, taskProfile, tokenBudget);
        appendIfBudgeted(context, MemoryLevel.L3_TASK, agent.getConfig().getSystemPrompt());
        for (Map<String, String> message : agent.getConversationHistory()) {
            appendIfBudgeted(context, MemoryLevel.L2_SESSION,
                    message.get("role") + ": " + message.get("content"));
        }
        return context;
    }

    private void appendIfBudgeted(AgentContext context, MemoryLevel level, String content) {
        String sanitized = sanitize(content);
        if (sanitized == null || sanitized.isEmpty()) {
            return;
        }
        context.appendMemory(MemoryEntry.of(level, sanitized, estimateTokens(sanitized)));
    }

    private String sanitize(String raw) {
        String result = raw;
        for (ContextSanitizer sanitizer : sanitizers) {
            if (result == null) {
                return null;
            }
            result = sanitizer.sanitize(result);
        }
        return result;
    }

    private long estimateTokens(String content) {
        return content.length() / 4L + 1L;
    }
}
