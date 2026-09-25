package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.plan.Plan;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H3 - 范式分发循环单测：按 paradigm 路由到对应循环，未注册范式降级 fallback。
 */
class ParadigmDispatchingExecutionLoopTest {

    private static class RecordingLoop implements ExecutionLoopService {
        final String name;
        int invocations = 0;

        RecordingLoop(String name) {
            this.name = name;
        }

        @Override
        public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
            invocations++;
            task.start();
            task.complete();
            return task;
        }
    }

    private ExecutionTask task(RuntimeParadigm paradigm) {
        return ExecutionTask.create("agent", "default", paradigm, TerminationGate.defaults());
    }

    private Agent agent() {
        return Agent.create(AgentConfig.defaultConfig("A"));
    }

    @Test
    void shouldDispatchToMatchingLoop() {
        RecordingLoop react = new RecordingLoop("react");
        RecordingLoop planExec = new RecordingLoop("plan");
        Map<RuntimeParadigm, ExecutionLoopService> loops = new EnumMap<>(RuntimeParadigm.class);
        loops.put(RuntimeParadigm.REACT, react);
        loops.put(RuntimeParadigm.PLAN_AND_EXECUTE, planExec);
        ParadigmDispatchingExecutionLoop dispatcher = new ParadigmDispatchingExecutionLoop(loops, react);

        dispatcher.execute(task(RuntimeParadigm.PLAN_AND_EXECUTE), agent(), new Plan("a"));

        assertEquals(1, planExec.invocations);
        assertEquals(0, react.invocations);
    }

    @Test
    void shouldFallbackForUnregisteredParadigm() {
        RecordingLoop react = new RecordingLoop("react");
        Map<RuntimeParadigm, ExecutionLoopService> loops = new EnumMap<>(RuntimeParadigm.class);
        loops.put(RuntimeParadigm.REACT, react);
        ParadigmDispatchingExecutionLoop dispatcher = new ParadigmDispatchingExecutionLoop(loops, react);

        ExecutionTask task = dispatcher.execute(task(RuntimeParadigm.WORKFLOW), agent(), new Plan("a"));

        assertEquals(1, react.invocations);
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
    }

    @Test
    void shouldThrowWhenNoLoopAndNoFallback() {
        ParadigmDispatchingExecutionLoop dispatcher =
                new ParadigmDispatchingExecutionLoop(Map.of(), null);

        assertThrows(IllegalStateException.class,
                () -> dispatcher.execute(task(RuntimeParadigm.REACT), agent(), new Plan("a")));
    }
}
