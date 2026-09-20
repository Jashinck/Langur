package org.skylark.langur.domain.harness.context;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * C 组件聚合根 - Agent 上下文。
 * <p>承载四级记忆、双画像与 Token 预算治理（预算分配制，防上下文溢出）。</p>
 */
@Getter
public class AgentContext {

    private final String contextId;
    private final String traceId;
    private final UserProfile userProfile;
    private final TaskProfile taskProfile;
    private final List<MemoryEntry> memories;
    private final long tokenBudget;
    private long consumedTokens;

    private AgentContext(String contextId, String traceId, UserProfile userProfile,
                         TaskProfile taskProfile, long tokenBudget) {
        this.contextId = contextId;
        this.traceId = traceId;
        this.userProfile = userProfile;
        this.taskProfile = taskProfile;
        this.memories = new ArrayList<>();
        this.tokenBudget = tokenBudget;
        this.consumedTokens = 0L;
    }

    public static AgentContext create(String traceId, UserProfile userProfile,
                                      TaskProfile taskProfile, long tokenBudget) {
        return new AgentContext(UUID.randomUUID().toString(), traceId,
                userProfile, taskProfile, tokenBudget);
    }

    /**
     * Token 预算治理：超预算的记忆写入被拒绝
     */
    public boolean appendMemory(MemoryEntry entry) {
        if (!hasTokenBudget(entry.getTokenEstimate())) {
            return false;
        }
        memories.add(entry);
        consumedTokens += entry.getTokenEstimate();
        return true;
    }

    public boolean hasTokenBudget(long required) {
        return consumedTokens + required <= tokenBudget;
    }

    public List<MemoryEntry> memoriesOf(MemoryLevel level) {
        return memories.stream().filter(m -> m.getLevel() == level).toList();
    }
}
