package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.StateSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 验收 - 断点续跑与并发重入保护：
 * 从最近快照轮次继续（而非从 0 开始）；锁被他人持有时并发重入被拒绝。
 */
class ResumeExecutionLoopTest {

    private static final String TASK_ID = "task-resume-1";

    @Test
    void shouldResumeFromLatestSnapshotRound() {
        MapTaskStateRepository repository = new MapTaskStateRepository();
        TaskState suspended = TaskState.init(TASK_ID);
        suspended.addSnapshot(StateSnapshot.of(TASK_ID, 3,
                Map.of("agentStatus", "RUNNING", "iteration", 3, "planSteps", 0)));
        repository.save(suspended);

        ExecutionTask task = ExecutionTask.resume(
                TASK_ID, "agent-1", "default", RuntimeParadigm.REACT, TerminationGate.defaults());
        ExecutionTask result = runLoop(repository, task, newAgent());

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        // 从快照 round=3 恢复后本轮 nextRound() → 4，证明未从 0 开始
        assertEquals(4, result.getCurrentRound());
    }

    @Test
    void shouldRejectConcurrentReentryWhenLockedByOther() {
        MapTaskStateRepository repository = new MapTaskStateRepository();
        TaskState locked = TaskState.init(TASK_ID);
        locked.acquireLock("other-holder");
        repository.save(locked);

        ExecutionTask task = ExecutionTask.resume(
                TASK_ID, "agent-1", "default", RuntimeParadigm.REACT, TerminationGate.defaults());
        ExecutionTask result = runLoop(repository, task, newAgent());

        assertEquals(ExecutionStatus.TERMINATED, result.getStatus());
        assertTrue(result.getTerminateReason().contains("locked"),
                "并发重入应被锁拒绝，实际原因：" + result.getTerminateReason());
    }

    private ExecutionTask runLoop(TaskStateRepository repository, ExecutionTask task, Agent agent) {
        LLMPort immediateAnswer = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.finalAnswer("done");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "done";
            }
        };
        Plan plan = new Plan(agent.getId().getValue());
        ReActExecutionLoop loop = new ReActExecutionLoop(
                new AgentDomainService(immediateAnswer), new LifecycleHookEngine(), repository, null);
        return loop.execute(task, agent, plan);
    }

    private Agent newAgent() {
        Agent agent = Agent.create(AgentConfig.defaultConfig("ResumeAgent"));
        agent.addUserMessage("continue");
        return agent;
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
