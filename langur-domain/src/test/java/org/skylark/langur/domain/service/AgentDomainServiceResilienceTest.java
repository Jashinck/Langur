package org.skylark.langur.domain.service;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.execution.RetryPolicy;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.DefaultFallbackStrategy;
import org.skylark.langur.domain.port.LLMPort;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7 验收 - 容错降级：工具失败重试后成功、重试耗尽走降级话术、LLM 异常兜底。
 */
class AgentDomainServiceResilienceTest {

    @Test
    void shouldRetryToolUntilSuccess() {
        FlakyTool tool = new FlakyTool(2);
        AgentDomainService service = newService(toolCallLlm());
        service.attachResilience(new RetryPolicy(3, 0L), new DefaultFallbackStrategy());

        Agent agent = agentWith(tool);
        Plan plan = new Plan(agent.getId().getValue());
        service.executeReActStep(agent, plan);

        PlanStep last = lastStep(plan);
        assertEquals(StepStatus.COMPLETED, last.getStatus());
        assertEquals("ok", last.getObservation());
        assertEquals(3, tool.calls, "前两次失败、第三次成功，共调用 3 次");
    }

    @Test
    void shouldDegradeWhenToolAlwaysFails() {
        FlakyTool tool = new FlakyTool(Integer.MAX_VALUE);
        AgentDomainService service = newService(toolCallLlm());
        service.attachResilience(new RetryPolicy(3, 0L), new DefaultFallbackStrategy());

        Agent agent = agentWith(tool);
        Plan plan = new Plan(agent.getId().getValue());
        service.executeReActStep(agent, plan);

        PlanStep last = lastStep(plan);
        assertEquals(StepStatus.FAILED, last.getStatus());
        assertTrue(last.getObservation().contains("降级"), "重试耗尽后应产出降级话术，实际：" + last.getObservation());
    }

    @Test
    void shouldFallbackWhenLlmThrows() {
        LLMPort throwing = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                throw new RuntimeException("boom");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                throw new RuntimeException("boom");
            }
        };
        AgentDomainService service = newService(throwing);
        service.attachResilience(RetryPolicy.noRetry(), new DefaultFallbackStrategy());

        Agent agent = Agent.create(AgentConfig.defaultConfig("FallbackAgent"));
        agent.addUserMessage("hi");
        String answer = service.executeReActStep(agent, new Plan(agent.getId().getValue()));

        assertTrue(answer.contains("模型服务暂时不可用"), "LLM 异常应返回兜底话术，实际：" + answer);
    }

    private AgentDomainService newService(LLMPort llmPort) {
        return new AgentDomainService(llmPort);
    }

    private LLMPort toolCallLlm() {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.toolCall("thinking", "flaky", Map.of("input", "x"));
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "";
            }
        };
    }

    private Agent agentWith(Tool tool) {
        Agent agent = Agent.create(AgentConfig.defaultConfig("ResilienceAgent"));
        agent.registerTool(tool);
        agent.addUserMessage("run");
        return agent;
    }

    private PlanStep lastStep(Plan plan) {
        return plan.getSteps().get(plan.getSteps().size() - 1);
    }

    /** 前 {@code failTimes} 次调用失败，之后成功；用于验证重试。 */
    private static class FlakyTool extends Tool {
        private final int failTimes;
        private int calls = 0;

        FlakyTool(int failTimes) {
            super(ToolDefinition.of("flaky", "flaky tool", Map.of()));
            this.failTimes = failTimes;
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            calls++;
            return calls <= failTimes ? ToolResult.failure("transient error") : ToolResult.success("ok");
        }
    }
}
