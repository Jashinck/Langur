package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.Plan;

import java.util.EnumMap;
import java.util.Map;

/**
 * 范式分发执行循环（H3，§5.3）- 按 {@link ExecutionTask#getParadigm()} 分发到对应范式的执行循环。
 * <p>三层混合 Runtime 的统一入口：REACT → {@code ReActExecutionLoop}，PLAN_AND_EXECUTE →
 * {@code PlanAndExecuteExecutionLoop}；未注册范式（如 WORKFLOW/HYBRID，H9 落地前）降级到 fallback（ReAct）。
 * 纯领域实现（零 Spring 依赖，P1），由 start 层装配。</p>
 */
public class ParadigmDispatchingExecutionLoop implements ExecutionLoopService {

    private final Map<RuntimeParadigm, ExecutionLoopService> loops;
    private final ExecutionLoopService fallback;

    public ParadigmDispatchingExecutionLoop(Map<RuntimeParadigm, ExecutionLoopService> loops,
                                            ExecutionLoopService fallback) {
        this.loops = new EnumMap<>(RuntimeParadigm.class);
        if (loops != null) {
            this.loops.putAll(loops);
        }
        this.fallback = fallback;
    }

    @Override
    public ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan) {
        ExecutionLoopService loop = loops.get(task.getParadigm());
        if (loop == null) {
            loop = fallback;
        }
        if (loop == null) {
            throw new IllegalStateException("No execution loop registered for paradigm " + task.getParadigm());
        }
        return loop.execute(task, agent, plan);
    }
}
