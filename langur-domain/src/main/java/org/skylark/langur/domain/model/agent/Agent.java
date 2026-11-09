package org.skylark.langur.domain.model.agent;

import lombok.Getter;
import org.skylark.langur.domain.event.AgentCreatedEvent;
import org.skylark.langur.domain.event.AgentExecutedEvent;
import org.skylark.langur.domain.event.DomainEvent;
import org.skylark.langur.domain.model.message.SessionEvent;
import org.skylark.langur.domain.model.message.ToolCall;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    /** 会话事件日志（H15.1）：append-only，替代原 {@code List<Map<String,String>>} conversationHistory。 */
    private final List<SessionEvent> sessionEvents;
    /** 事件序列号计数器（H15.3 快照事件游标用），随 append 单调递增。 */
    private long eventSequence;
    private final List<DomainEvent> domainEvents;
    private int iterationCount;
    private String lastError;
    /** 本轮 LLM 决策回传的真实 Token 计量（H1）；由执行循环消费后清空。 */
    private LLMPort.TokenUsage lastRoundUsage;
    private final Instant createdAt;
    private Instant updatedAt;

    private Agent(AgentId id, AgentConfig config, Instant createdAt, Instant updatedAt, boolean recordCreationEvent) {
        this.id = id;
        this.config = config;
        this.status = AgentStatus.IDLE;
        this.tools = new ArrayList<>();
        this.registeredToolNames = new ArrayList<>();
        this.sessionEvents = new ArrayList<>();
        this.eventSequence = 0L;
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
                                 List<String> registeredToolNames, List<SessionEvent> sessionEvents,
                                 int iterationCount, String lastError, Instant createdAt,
                                 Instant updatedAt) {
        Agent agent = new Agent(id, config, createdAt, updatedAt, false);
        agent.status = status;
        agent.registeredToolNames.addAll(registeredToolNames);
        agent.sessionEvents.addAll(sessionEvents);
        agent.eventSequence = sessionEvents.stream().mapToLong(SessionEvent::sequence).max().orElse(-1L) + 1L;
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
        sessionEvents.add(SessionEvent.user(eventSequence++, content));
        updatedAt = Instant.now();
    }

    public void addAssistantMessage(String content) {
        sessionEvents.add(SessionEvent.assistant(eventSequence++, content));
        updatedAt = Instant.now();
    }

    /** 记录助手原生工具调用（H15.1）：单条 assistant 事件携带全部 tool_calls，供后续 tool 结果回指。 */
    public void addAssistantToolCalls(String thought, List<ToolCall> toolCalls) {
        sessionEvents.add(SessionEvent.assistantToolCalls(eventSequence++, thought, toolCalls));
        updatedAt = Instant.now();
    }

    public void addToolResultMessage(String toolCallId, String toolName, String result) {
        sessionEvents.add(SessionEvent.tool(eventSequence++, toolCallId, toolName, result));
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
     * [L4 输出安全] 应用输出合规钩子（BEFORE_OUTPUT MODIFY）改写最终答案。
     * <p>H15.1：事件日志只增不改——追加一条修正 assistant 事件；下游"取最后一条 assistant"的
     * 视图投影（AgentAssembler/循环 lastAssistantMessage）自然读到修正后的答案，不再倒序扫描改写。</p>
     */
    public void overrideFinalAnswer(String newAnswer) {
        addAssistantMessage(newAnswer);
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

    /**
     * 记录本轮 LLM 决策回传的真实 Token 计量（H1）。null / 空计量忽略，交由调用方降级估算。
     */
    public void recordTokenUsage(LLMPort.TokenUsage usage) {
        if (usage != null && !usage.isEmpty()) {
            this.lastRoundUsage = usage;
            this.updatedAt = Instant.now();
        }
    }

    /**
     * 取出并清空本轮真实 Token 计量（H1）；返回 {@code null} 表示无真实计量，调用方降级为字符估算。
     */
    public LLMPort.TokenUsage consumeLastRoundUsage() {
        LLMPort.TokenUsage usage = this.lastRoundUsage;
        this.lastRoundUsage = null;
        return usage;
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

    public List<SessionEvent> getSessionEvents() {
        return Collections.unmodifiableList(sessionEvents);
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
