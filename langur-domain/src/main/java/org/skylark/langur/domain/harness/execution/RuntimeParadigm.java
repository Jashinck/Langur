package org.skylark.langur.domain.harness.execution;

/**
 * 运行范式 - 三层混合 Runtime 架构
 */
public enum RuntimeParadigm {
    /** 顶层工作流：Harness 全权，锁定合规边界/审批/强约束 */
    WORKFLOW,
    /** 中层规划执行：LLM 规划 + Harness 校验，全局任务拆解 */
    PLAN_AND_EXECUTE,
    /** 底层推理行动：LLM 自主 + Harness 兜底，细粒度工具推理 */
    REACT,
    /** 三层混合范式 */
    HYBRID
}
