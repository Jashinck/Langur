package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.Plan;

/**
 * E 组件核心 - 执行循环服务（端口）。
 * <p>驱动模型推理决策，协同 T/S/L/V 组件；默认实现为 ReAct 循环（{@code ReActExecutionLoop}）。</p>
 */
public interface ExecutionLoopService {

    /**
     * 驱动一次完整执行循环。
     *
     * @param task  执行任务（承载终止闸门与计量）
     * @param agent 目标 Agent 聚合根
     * @param plan  执行计划（思维链追踪）
     * @return 终态执行任务
     */
    ExecutionTask execute(ExecutionTask task, Agent agent, Plan plan);
}
