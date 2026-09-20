package org.skylark.langur.domain.model.agent;

import lombok.Getter;
import org.skylark.langur.domain.event.AgentCreatedEvent;
import org.skylark.langur.domain.event.AgentExecutedEvent;
import org.skylark.langur.domain.event.DomainEvent;
import org.skylark.langur.domain.model.tool.Tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent聚合根 - 代表一个具备自主决策和工具调用能力的智能体
 */
@Getter
public class Agent {

    private final AgentId id;
    private AgentConfig config;
    private AgentStatus status;
    private final List<Tool> tools;
    private final List<String> registeredToolNames;
    private final List<Map<String, String>> conversationHistory;
    private final List<DomainEvent> domainEvents;
    private int iterationCount;
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;

    private Agent(AgentId id, AgentConfig config, Instant createdAt, Instant updatedAt, boolean recordCreationEvent) {
        this.id = id;
        this.config = config;
        this.status = AgentStatus.IDLE;
        this.tools = new ArrayList<>();
        this.registeredToolNames = new ArrayList<>();
        this.conversationHistory = new ArrayList<>();
        this.domainEvents = new ArrayList<>();
        this.iterationCount = 0;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        if (recordCreationEvent) {
            recordEvent(new AgentCreatedEvent(id.getValue(), config.getName()));
        }
    }

    public static Agent create(AgentConfig config) {
        Instant now = Instant.now();
        return new Agent(AgentId.generate(), config, now, now, true);
    }

    public static Agent restore(AgentId id, AgentConfig config, AgentStatus status,
                                 List<String> registeredToolNames, List<Map<String, String>> history,
                                 int iterationCount, String lastError, Instant createdAt,
                                 Instant updatedAt) {
        Agent agent = new Agent(id, config, createdAt, updatedAt, false);
        agent.status = status;
        agent.registeredToolNames.addAll(registeredToolNames);
        agent.conversationHistory.addAll(history);
        agent.iterationCount = iterationCount;
        agent.lastError = lastError;
        return agent;
    }

    public void registerTool(Tool tool) {
        if (tools.stream().anyMatch(t -> t.getName().equals(tool.getName()))) {
            throw new IllegalArgumentException("Tool already registered: " + tool.getName());
        }
        tools.add(tool);
        if (!registeredToolNames.contains(tool.getName())) {
            registeredToolNames.add(tool.getName());
        }
        updatedAt = Instant.now();
    }

    public void addUserMessage(String content) {
        conversationHistory.add(Map.of("role", "user", "content", content));
        updatedAt = Instant.now();
    }

    public void addAssistantMessage(String content) {
        conversationHistory.add(Map.of("role", "assistant", "content", content));
        updatedAt = Instant.now();
    }

    public void addToolResultMessage(String toolName, String result) {
        conversationHistory.add(Map.of("role", "tool", "name", toolName, "content", result));
        updatedAt = Instant.now();
    }

    public void markRunning() {
        validateTransition(AgentStatus.RUNNING);
        this.status = AgentStatus.RUNNING;
        this.lastError = null;
        updatedAt = Instant.now();
    }

    public void markCompleted(String finalAnswer) {
        this.status = AgentStatus.COMPLETED;
        addAssistantMessage(finalAnswer);
        recordEvent(new AgentExecutedEvent(id.getValue(), iterationCount, finalAnswer));
        updatedAt = Instant.now();
    }

    /**
     * [L4 输出安全] 应用输出合规钩子（BEFORE_OUTPUT MODIFY）改写最终答案：
     * 覆盖会话历史中最近一条 assistant 消息内容，使下游装配读到合规后的答案。
     */
    public void overrideFinalAnswer(String newAnswer) {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            Map<String, String> message = conversationHistory.get(i);
            if ("assistant".equals(message.get("role"))) {
                conversationHistory.set(i, Map.of("role", "assistant", "content", newAnswer));
                break;
            }
        }
        updatedAt = Instant.now();
    }

    /**
     * [S] 断点续跑：从最近快照恢复迭代计数（T5）。
     */
    public void resumeIteration(int count) {
        this.iterationCount = count;
        updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = AgentStatus.FAILED;
        this.lastError = error;
        updatedAt = Instant.now();
    }

    public void incrementIteration() {
        this.iterationCount++;
    }

    public boolean hasExceededMaxIterations() {
        return iterationCount >= config.getMaxIterations();
    }

    public List<Tool> getTools() {
        return Collections.unmodifiableList(tools);
    }

    public List<String> getRegisteredToolNames() {
        return Collections.unmodifiableList(registeredToolNames);
    }

    public List<Map<String, String>> getConversationHistory() {
        return Collections.unmodifiableList(conversationHistory);
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = new ArrayList<>(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void recordEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    private void validateTransition(AgentStatus target) {
        if (status == AgentStatus.RUNNING && target == AgentStatus.RUNNING) {
            throw new IllegalStateException("Agent is already running");
        }
    }
}
