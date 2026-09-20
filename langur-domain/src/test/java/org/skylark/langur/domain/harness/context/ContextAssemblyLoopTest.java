package org.skylark.langur.domain.harness.context;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.execution.ExecutionStatus;
import org.skylark.langur.domain.harness.execution.ExecutionTask;
import org.skylark.langur.domain.harness.execution.ReActExecutionLoop;
import org.skylark.langur.domain.harness.execution.RuntimeParadigm;
import org.skylark.langur.domain.harness.execution.TerminationGate;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskState;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T3 验收 - C 组件上下文装配接入执行循环：
 * 执行链路产生持久化的 AgentContext；超 Token 预算的记忆写入被拒绝。
 */
class ContextAssemblyLoopTest {

    @Test
    void loopShouldAssembleAndPersistAgentContext() {
        AtomicReference<Long> budgetSeen = new AtomicReference<>();
        ContextAssembler assembler = (traceId, bizCode, agent, tokenBudget) -> {
            budgetSeen.set(tokenBudget);
            AgentContext context = AgentContext.create(traceId,
                    UserProfile.builder().userId(agent.getId().getValue()).build(),
                    TaskProfile.builder().bizCode(bizCode).build(),
                    tokenBudget);
            context.appendMemory(MemoryEntry.of(MemoryLevel.L2_SESSION, "hello", 2L));
            return context;
        };
        RecordingContextRepository repository = new RecordingContextRepository();

        ExecutionTask task = runLoop(assembler, repository);

        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertEquals(1, repository.saved.size(), "执行链路应持久化一份 AgentContext");
        AgentContext saved = repository.saved.get(0);
        assertEquals(task.getTraceId(), saved.getTraceId());
        assertEquals(1, saved.memoriesOf(MemoryLevel.L2_SESSION).size());
        assertEquals(TerminationGate.defaults().getMaxTokens(), budgetSeen.get(),
                "Token 预算应取自任务终止闸门");
    }

    @Test
    void loopShouldSkipContextAssemblyWhenAssemblerAbsent() {
        ExecutionTask task = runLoop(null, new RecordingContextRepository());
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
    }

    @Test
    void shouldRejectMemoryWriteBeyondTokenBudget() {
        AgentContext context = AgentContext.create("trace-1",
                UserProfile.builder().userId("u-1").build(),
                TaskProfile.builder().bizCode("default").build(),
                10L);

        assertTrue(context.appendMemory(MemoryEntry.of(MemoryLevel.L2_SESSION, "short", 5L)));
        assertFalse(context.appendMemory(MemoryEntry.of(MemoryLevel.L2_SESSION, "too long", 6L)),
                "超出 Token 预算的记忆写入必须被拒绝");
        assertEquals(1, context.getMemories().size());
        assertEquals(5L, context.getConsumedTokens());
    }

    private ExecutionTask runLoop(ContextAssembler assembler, AgentContextRepository repository) {
        LLMPort immediateAnswer = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> conversationHistory, List<Tool> availableTools) {
                return LLMDecision.finalAnswer("done");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "done";
            }
        };
        Agent agent = Agent.create(AgentConfig.defaultConfig("ContextAgent"));
        agent.addUserMessage("hi");
        Plan plan = new Plan(agent.getId().getValue());

        AgentDomainService domainService = new AgentDomainService(immediateAnswer);
        ReActExecutionLoop loop = new ReActExecutionLoop(domainService, new LifecycleHookEngine(),
                new MapTaskStateRepository(), null, assembler, repository);

        ExecutionTask task = ExecutionTask.create(
                agent.getId().getValue(), "default", RuntimeParadigm.REACT, TerminationGate.defaults());
        return loop.execute(task, agent, plan);
    }

    private static class RecordingContextRepository implements AgentContextRepository {
        final List<AgentContext> saved = new ArrayList<>();

        @Override
        public void save(AgentContext context) {
            saved.add(context);
        }

        @Override
        public Optional<AgentContext> findById(String contextId) {
            return saved.stream().filter(c -> c.getContextId().equals(contextId)).findFirst();
        }
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
