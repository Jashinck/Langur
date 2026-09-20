package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4 验收 - BEFORE_OUTPUT / AFTER_OUTPUT 输出合规拦截点生效：
 * 输出改写钩子可 MODIFY 最终答案；ABORT 可阻断返回并终止任务。
 */
class OutputComplianceHookTest {

    @Test
    void beforeOutputHookShouldRewriteFinalAnswer() {
        LifecycleHookEngine engine = new LifecycleHookEngine();
        engine.register(new LifecycleHook() {
            @Override
            public HookPoint point() {
                return HookPoint.BEFORE_OUTPUT;
            }

            @Override
            public HookResult execute(HookContext context) {
                return HookResult.modify(context.getPayload().replace("secret-123", "***"));
            }
        });

        Agent agent = newAgent();
        ExecutionTask task = runLoop(engine, agent);

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals("token ***", lastAssistantMessage(agent), "最终答案应被 BEFORE_OUTPUT 钩子改写后返回");
    }

    @Test
    void beforeOutputHookAbortShouldTerminate() {
        LifecycleHookEngine engine = new LifecycleHookEngine();
        engine.register(new LifecycleHook() {
            @Override
            public HookPoint point() {
                return HookPoint.BEFORE_OUTPUT;
            }

            @Override
            public HookResult execute(HookContext context) {
                return HookResult.abort("content policy violation");
            }
        });

        ExecutionTask task = runLoop(engine, newAgent());

        assertEquals(ExecutionStatus.TERMINATED, task.getStatus());
        assertEquals("Output rejected by lifecycle hook", task.getTerminateReason());
    }

    private ExecutionTask runLoop(LifecycleHookEngine engine, Agent agent) {
        LLMPort answer = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.finalAnswer("token secret-123");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "token secret-123";
            }
        };
        Plan plan = new Plan(agent.getId().getValue());
        ReActExecutionLoop loop = new ReActExecutionLoop(
                new AgentDomainService(answer), engine, new MapTaskStateRepository(), null);
        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.REACT, TerminationGate.defaults());
        return loop.execute(task, agent, plan);
    }

    private Agent newAgent() {
        Agent agent = Agent.create(AgentConfig.defaultConfig("OutputAgent"));
        agent.addUserMessage("hi");
        return agent;
    }

    private String lastAssistantMessage(Agent agent) {
        List<Map<String, String>> history = agent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).get("role"))) {
                return history.get(i).get("content");
            }
        }
        return null;
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
