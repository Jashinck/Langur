package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T7 验收（执行链路）- 循环检测触发强制终止，终止原因为 LOOP_DETECTED。
 */
class LoopDetectionExecutionTest {

    @Test
    void shouldTerminateWithLoopDetectedWhenRepeatingSameAction() {
        LLMPort endlessSameToolCall = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.toolCall("thinking", "echo", Map.of("input", "x"));
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "";
            }
        };

        Agent agent = Agent.create(AgentConfig.defaultConfig("LoopAgent"));
        agent.registerTool(new Tool(ToolDefinition.of("echo", "echo tool", Map.of())) {
            @Override
            public ToolResult execute(Map<String, Object> parameters) {
                return ToolResult.success("same");
            }
        });
        agent.addUserMessage("run");
        Plan plan = new Plan(agent.getId().getValue());

        ReActExecutionLoop loop = new ReActExecutionLoop(
                new AgentDomainService(endlessSameToolCall), new LifecycleHookEngine(),
                new MapTaskStateRepository(), null);
        loop.attachLoopDetector(new LoopDetector(6, 3));

        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.REACT, TerminationGate.defaults());
        ExecutionTask result = loop.execute(task, agent, plan);

        assertEquals(ExecutionStatus.TERMINATED, result.getStatus());
        assertEquals("LOOP_DETECTED", result.getTerminateReason());
        assertEquals(3, result.getCurrentRound(), "第三次重复即触发循环终止");
    }

    private static class MapTaskStateRepository implements TaskStateRepository {
        private final Map<String, TaskState> store = new HashMap<>();

        @Override
        public void save(TaskState state) {
            store.put(state.getTaskId(), state);
        }

        @Override
        public Optional<TaskState> findById(String taskId) {
            return Optional.ofNullable(store.get(taskId));
        }
    }
}
