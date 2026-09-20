package org.skylark.langur.domain.harness.execution;

/**
 * 分层路由器 - 按任务特征路由到对应执行层与范式。
 * <pre>
 * 强合规/审批类   → 顶层 Workflow
 * 多步骤复杂任务 → 中层 PlanAndExecute
 * 单轮推理/开放问答 → 底层 ReAct
 * 默认（未指定）  → ReAct
 * </pre>
 */
public interface LayerRouter {

    RuntimeLayer route(String bizCode, String userMessage);

    default RuntimeParadigm paradigmOf(RuntimeLayer layer) {
        return switch (layer) {
            case WORKFLOW_LAYER -> RuntimeParadigm.WORKFLOW;
            case PLAN_LAYER -> RuntimeParadigm.PLAN_AND_EXECUTE;
            case REACT_LAYER -> RuntimeParadigm.REACT;
        };
    }
}
