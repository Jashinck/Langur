package org.skylark.langur.domain.harness.execution;

/**
 * 三层混合 Runtime 的分层标识
 */
public enum RuntimeLayer {
    WORKFLOW_LAYER,
    PLAN_LAYER,
    REACT_LAYER,
    /** 分层混合：顶层 Workflow 锁边界 → 中层 PlanAndExecute 拆解 → 底层 ReAct 执行（H9）。 */
    HYBRID_LAYER
}
