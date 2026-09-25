package org.skylark.langur.infrastructure.harness.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.config.LlmProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 - LLM 规划器单测：JSON 步骤解析（含围栏噪声容错）、非法输出解析为空、
 * 调用异常/无步骤时降级启发式规划（P10）。
 */
class LlmPlannerTest {

    private LlmPlanner plannerReturning(String completion) {
        return new LlmPlanner(new LlmGateway(stubPort(completion, false), new LlmProperties()), new ObjectMapper());
    }

    private LlmPlanner plannerThrowing() {
        return new LlmPlanner(new LlmGateway(stubPort(null, true), new LlmProperties()), new ObjectMapper());
    }

    private Agent agent() {
        return Agent.create(AgentConfig.defaultConfig("PlanAgent"));
    }

    @Test
    void shouldParseJsonArrayIntoSteps() {
        LlmPlanner planner = plannerReturning("[]");
        List<PlanStep> steps = planner.parse("[{\"thought\":\"采集\",\"action\":\"react\"},{\"thought\":\"分析\"}]");

        assertEquals(2, steps.size());
        assertEquals("采集", steps.get(0).getThought());
        assertEquals("react", steps.get(0).getAction());
        assertEquals("分析", steps.get(1).getThought());
        assertEquals("react", steps.get(1).getAction(), "缺省 action 应回退 react");
    }

    @Test
    void shouldParseStepsFromNoisyOutput() {
        LlmPlanner planner = plannerReturning("[]");
        List<PlanStep> steps = planner.parse("好的，规划如下：\n```json\n[{\"thought\":\"X\"}]\n```");

        assertEquals(1, steps.size());
        assertEquals("X", steps.get(0).getThought());
    }

    @Test
    void shouldReturnEmptyForInvalidJson() {
        LlmPlanner planner = plannerReturning("[]");

        assertTrue(planner.parse("抱歉，我无法规划").isEmpty());
        assertTrue(planner.parse(null).isEmpty());
        assertTrue(planner.parse("{not an array}").isEmpty());
    }

    @Test
    void shouldPlanViaLlmWhenOutputValid() {
        LlmPlanner planner = plannerReturning("[{\"thought\":\"步骤一\"},{\"thought\":\"步骤二\"}]");

        List<PlanStep> steps = planner.plan("目标", agent());

        assertEquals(2, steps.size());
        assertEquals("步骤一", steps.get(0).getThought());
    }

    @Test
    void shouldDegradeToHeuristicWhenOutputNotSteps() {
        LlmPlanner planner = plannerReturning("我无法输出结构化规划");

        List<PlanStep> steps = planner.plan("首先 采集 然后 汇总", agent());

        assertFalse(steps.isEmpty());
        assertEquals(2, steps.size(), "降级启发式应按序列标记拆解");
    }

    @Test
    void shouldDegradeToHeuristicWhenGatewayThrows() {
        LlmPlanner planner = plannerThrowing();

        List<PlanStep> steps = planner.plan("回答问题", agent());

        assertFalse(steps.isEmpty());
        assertEquals("回答问题", steps.get(0).getThought());
    }

    private LLMPort stubPort(String completion, boolean throwOnComplete) {
        return new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.finalAnswer("noop");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                if (throwOnComplete) {
                    throw new IllegalStateException("provider down");
                }
                return completion;
            }
        };
    }
}
