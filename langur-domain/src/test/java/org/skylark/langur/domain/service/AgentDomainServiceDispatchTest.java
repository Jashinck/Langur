package org.skylark.langur.domain.service;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.LLMPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1 验收 - 领域服务工具调用必经 T 组件统一调度器（§1.2 模型与环境隔离），
 * 装配调度器后不存在 {@code tool.execute} 直调路径。
 */
class AgentDomainServiceDispatchTest {

    @Test
    void shouldRouteToolCallThroughDispatcherWithoutDirectExecution() {
        CountingTool tool = new CountingTool("echo");
        RecordingDispatcher dispatcher = new RecordingDispatcher(ToolCallResult.success("dispatched-ok", 3));
        Agent agent = agentWithTool(tool);
        Plan plan = new Plan(agent.getId().getValue());
        AgentDomainService service = new AgentDomainService(singleToolCallLLM());
        service.attachToolDispatcher(dispatcher);

        String answer = service.executeReActStep(agent, plan);

        assertNull(answer);
        assertEquals(1, dispatcher.callCount.get());
        assertEquals("echo", dispatcher.lastRequest.getToolId());
        assertEquals(agent.getId().getValue(), dispatcher.lastRequest.getCaller());
        assertEquals(0, tool.executions.get(), "装配调度器后不允许直调 tool.execute");
        assertEquals("dispatched-ok", plan.getSteps().get(0).getObservation());
    }

    @Test
    void shouldReflectDispatcherRejectionAsToolFailure() {
        CountingTool tool = new CountingTool("echo");
        RecordingDispatcher dispatcher = new RecordingDispatcher(
                ToolCallResult.failure("Rejected: caller not in whitelist", 1));
        Agent agent = agentWithTool(tool);
        Plan plan = new Plan(agent.getId().getValue());
        AgentDomainService service = new AgentDomainService(singleToolCallLLM());
        service.attachToolDispatcher(dispatcher);

        service.executeReActStep(agent, plan);

        assertEquals(0, tool.executions.get());
        Map<String, String> lastMessage = agent.getConversationHistory()
                .get(agent.getConversationHistory().size() - 1);
        assertTrue(lastMessage.get("content").contains("Error: Rejected"));
    }

    @Test
    void shouldFallbackToDirectExecutionWhenDispatcherAbsent() {
        CountingTool tool = new CountingTool("echo");
        Agent agent = agentWithTool(tool);
        Plan plan = new Plan(agent.getId().getValue());
        AgentDomainService service = new AgentDomainService(singleToolCallLLM());

        service.executeReActStep(agent, plan);

        assertEquals(1, tool.executions.get(), "未装配调度器时保留直调兜底（向后兼容）");
        assertEquals("direct-echo", plan.getSteps().get(0).getObservation());
    }

    private Agent agentWithTool(Tool tool) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("DispatchAgent"));
        agent.registerTool(tool);
        agent.addUserMessage("run echo");
        return agent;
    }

    private LLMPort singleToolCallLLM() {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> conversationHistory, List<Tool> availableTools) {
                return LLMDecision.toolCall("try echo", "echo", Map.of("input", "hi"));
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "";
            }
        };
    }

    private static class CountingTool extends Tool {
        final AtomicInteger executions = new AtomicInteger();

        CountingTool(String name) {
            super(ToolDefinition.of(name, "echo tool", Map.of()));
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            executions.incrementAndGet();
            return ToolResult.success("direct-echo");
        }
    }

    private static class RecordingDispatcher implements ToolDispatcher {
        final AtomicInteger callCount = new AtomicInteger();
        final ToolCallResult result;
        volatile ToolCallRequest lastRequest;

        RecordingDispatcher(ToolCallResult result) {
            this.result = result;
        }

        @Override
        public ToolCallResult dispatch(ToolCallRequest request) {
            callCount.incrementAndGet();
            lastRequest = request;
            return result;
        }
    }
}
