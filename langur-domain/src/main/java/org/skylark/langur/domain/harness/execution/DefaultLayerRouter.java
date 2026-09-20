package org.skylark.langur.domain.harness.execution;

/**
 * {@link LayerRouter} 默认实现（规则路由，§5.3）。
 * <pre>
 * 强合规/审批类 bizCode → 顶层 Workflow
 * 多步骤/规划类任务特征 → 中层 PlanAndExecute
 * 其余（含未指定）      → 底层 ReAct
 * </pre>
 * <p>纯领域实现（零 Spring 依赖），由 start 层装配为 Bean。
 * {@code isWorkflowBizCode} / {@code isPlanTask} 为 protected 扩展点，业务可覆写注入自定义路由规则。</p>
 */
public class DefaultLayerRouter implements LayerRouter {

    @Override
    public RuntimeLayer route(String bizCode, String userMessage) {
        if (isWorkflowBizCode(bizCode)) {
            return RuntimeLayer.WORKFLOW_LAYER;
        }
        if (isPlanTask(userMessage)) {
            return RuntimeLayer.PLAN_LAYER;
        }
        return RuntimeLayer.REACT_LAYER;
    }

    protected boolean isWorkflowBizCode(String bizCode) {
        if (bizCode == null) {
            return false;
        }
        String code = bizCode.toLowerCase();
        return code.contains("workflow") || code.contains("approval") || code.contains("compliance");
    }

    protected boolean isPlanTask(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String message = userMessage.toLowerCase();
        return message.contains("plan") || message.contains("step by step")
                || message.contains("多步") || message.contains("分步") || message.contains("规划");
    }
}
