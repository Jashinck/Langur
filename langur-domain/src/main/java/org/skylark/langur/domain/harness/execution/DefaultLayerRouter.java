package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.harness.workflow.WorkflowRepository;

/**
 * {@link LayerRouter} 默认实现（规则路由，§5.3 / H9）。
 * <pre>
 * 强合规/审批 + 多步骤 → 分层混合 Hybrid（顶层锁边界 → 中层拆解 → 底层执行）
 * 强合规/审批类 bizCode → 顶层 Workflow
 * 多步骤/规划类任务特征 → 中层 PlanAndExecute
 * 其余（含未指定）      → 底层 ReAct
 * </pre>
 * <p>纯领域实现（零 Spring 依赖），由 start 层装配为 Bean。
 * {@code isWorkflowBizCode} / {@code isPlanTask} 为 protected 扩展点，业务可覆写注入自定义路由规则。
 * 学习型路由（接 R4 反馈闭环）属 RSI 范畴，暂不在此实现，当前为确定性规则路由，决策由执行引擎埋点支撑。</p>
 * <p>配置驱动（P9）：可选注入 {@link WorkflowRepository}，凡已为该 bizCode 注册工作流定义者，
 * 一律视为 Workflow 信号，使 config 注册的多阶段工作流无需依赖 bizCode 命名约定即可生效。</p>
 */
public class DefaultLayerRouter implements LayerRouter {

    /** 可选工作流仓储：存在定义即视为 workflow 信号（P9）；为 null 时仅按命名约定判定。 */
    private final WorkflowRepository workflowRepository;

    public DefaultLayerRouter() {
        this(null);
    }

    public DefaultLayerRouter(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    @Override
    public RuntimeLayer route(String bizCode, String userMessage) {
        boolean workflow = isWorkflowBizCode(bizCode);
        boolean plan = isPlanTask(userMessage);
        if (workflow && plan) {
            return RuntimeLayer.HYBRID_LAYER;
        }
        if (workflow) {
            return RuntimeLayer.WORKFLOW_LAYER;
        }
        if (plan) {
            return RuntimeLayer.PLAN_LAYER;
        }
        return RuntimeLayer.REACT_LAYER;
    }

    protected boolean isWorkflowBizCode(String bizCode) {
        if (bizCode == null) {
            return false;
        }
        if (workflowRepository != null && workflowRepository.findByBizCode(bizCode).isPresent()) {
            return true;
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
