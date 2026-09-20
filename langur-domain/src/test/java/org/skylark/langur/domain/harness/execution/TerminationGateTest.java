package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2 验收 - 终止闸门四维硬约束（轮次 / Token / 单轮调用数 / 超时）全部生效。
 */
class TerminationGateTest {

    @Test
    void shouldTripOnTokenOverflow() {
        TerminationGate gate = TerminationGate.builder()
                .maxRounds(10).maxTokens(100).maxTimeout(Duration.ofMinutes(5)).maxCallsPerRound(10)
                .build();
        ExecutionTask task = ExecutionTask.create("agent-1", "default", RuntimeParadigm.REACT, gate);
        task.start();
        assertFalse(task.gateTripped());

        task.addTokens(150);

        assertTrue(gate.exceedsTokens(task.getConsumedTokens()));
        assertTrue(task.gateTripped());
    }

    @Test
    void shouldTripOnCallsPerRoundOverflow() {
        TerminationGate gate = TerminationGate.builder()
                .maxRounds(10).maxTokens(10_000).maxTimeout(Duration.ofMinutes(5)).maxCallsPerRound(2)
                .build();
        ExecutionTask task = ExecutionTask.create("agent-1", "default", RuntimeParadigm.REACT, gate);
        task.start();
        task.recordToolCall();
        assertFalse(task.gateTripped());

        task.recordToolCall();

        assertTrue(task.gateTripped());
        task.nextRound();
        assertFalse(task.gateTripped()); // 进入新一轮后单轮计数归零
    }

    @Test
    void shouldTripOnTimeout() {
        TerminationGate gate = TerminationGate.builder()
                .maxRounds(10).maxTokens(10_000).maxTimeout(Duration.ofMinutes(5)).maxCallsPerRound(10)
                .build();
        assertTrue(gate.exceedsTimeout(Instant.now().minus(Duration.ofMinutes(6))));
        assertFalse(gate.exceedsTimeout(Instant.now()));
        assertFalse(gate.exceedsTimeout(null));
    }

    @Test
    void loopShouldTerminateWhenTokenGateTripped() {
        ExecutionTask task = runLoopWithGate(TerminationGate.builder()
                .maxRounds(10).maxTokens(1).maxTimeout(Duration.ofMinutes(5)).maxCallsPerRound(10)
                .build());

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Termination gate tripped", task.getTerminateReason());
        assertTrue(task.getConsumedTokens() >= 1);
    }

    @Test
    void loopShouldTerminateWhenCallsPerRoundGateTripped() {
        ExecutionTask task = runLoopWithGate(TerminationGate.builder()
                .maxRounds(10).maxTokens(10_000_000).maxTimeout(Duration.ofMinutes(5)).maxCallsPerRound(1)
                .build());

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Termination gate tripped", task.getTerminateReason());
    }

    @Test
    void loopShouldTerminateWhenTimeoutGateTripped() {
        ExecutionTask task = runLoopWithGate(TerminationGate.builder()
                .maxRounds(10).maxTokens(10_000_000).maxTimeout(Duration.ofMillis(1)).maxCallsPerRound(10)
                .build());

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Termination gate tripped", task.getTerminateReason());
    }

    /**
     * 驱动执行循环：LLM 永远返回工具调用，直到闸门触发终止
     */
    private ExecutionTask runLoopWithGate(TerminationGate gate) {
        LLMPort endlessToolCall = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> conversationHistory, List<Tool> availableTools) {
                return LLMDecision.toolCall("thinking", "echo", Map.of("input", "x"));
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "";
            }
        };
        Agent agent = Agent.create(AgentConfig.defaultConfig("GateAgent"));
        agent.registerTool(new Tool(ToolDefinition.of("echo", "echo tool", Map.of())) {
            @Override
            public ToolResult execute(Map<String, Object> parameters) {
                return ToolResult.success("echoed");
            }
        });
        agent.addUserMessage("run");
        Plan plan = new Plan(agent.getId().getValue());

        AgentDomainService domainService = new AgentDomainService(endlessToolCall);
        ReActExecutionLoop loop = new ReActExecutionLoop(
                domainService, new LifecycleHookEngine(), new MapTaskStateRepository(), null);

        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.REACT, gate);
        return loop.execute(task, agent, plan);
    }

    /**
     * 测试用内存状态仓储（domain 测试不依赖 infrastructure）
     */
    private static class MapTaskStateRepository implements TaskStateRepository {
        private final Map<String, org.skylark.langur.domain.harness.state.TaskState> store = new HashMap<>();

        @Override
        public void save(org.skylark.langur.domain.harness.state.TaskState state) {
            store.put(state.getTaskId(), state);
        }

        @Override
        public Optional<org.skylark.langur.domain.harness.state.TaskState> findById(String taskId) {
            return Optional.ofNullable(store.get(taskId));
        }
    }
}
