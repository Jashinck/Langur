package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.PlanStep;

import java.util.List;

/**
 * 规划器端口（H3，中层 PlanAndExecute）- 将用户目标拆解为有序 {@link PlanStep}，并支持失败后动态重规划。
 * <p>纯领域端口（零外部依赖，P1）：默认 {@link HeuristicPlanner} 离线可测；
 * 基础设施层可提供 LLM（M5）规划实现，规划失败须降级 {@link HeuristicPlanner}（P10）。</p>
 */
public interface Planner {

    /**
     * 初始规划：把目标拆解为按序执行的子步骤。
     *
     * @param goal  用户目标（最近一条 user 消息）
     * @param agent 目标 Agent（可读取工具/画像辅助拆解）
     * @return 有序子步骤；实现须保证非空（无法拆解时退化为单步）
     */
    List<PlanStep> plan(String goal, Agent agent);

    /**
     * 动态重规划：某步失败后，基于已执行轨迹产出后续修订步骤。
     * <p>默认退化为重新整体规划；LLM 实现可结合失败原因做针对性修订。</p>
     *
     * @param executed      已执行（含失败）的高层步骤轨迹
     * @param failedStep    触发重规划的失败步骤
     * @param failureReason 失败原因
     * @return 修订后的后续步骤；返回空表示无需重规划（交由降级处理）
     */
    default List<PlanStep> replan(String goal, Agent agent, List<PlanStep> executed,
                                  PlanStep failedStep, String failureReason) {
        return plan(goal, agent);
    }
}
