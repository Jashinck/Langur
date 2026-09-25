package org.skylark.langur.domain.harness.execution;

import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.harness.workflow.WorkflowRepository;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.Locale;
import java.util.Map;

/**
 * {@link LayerRouter} 默认实现（规则路由，§5.3 / H9）。
 * <pre>
 * 强合规/审批 + 多步骤 → 分层混合 Hybrid（顶层锁边界 → 中层拆解 → 底层执行）
 * 强合规/审批类 bizCode → 顶层 Workflow
 * 多步骤/规划类任务特征 → 中层 PlanAndExecute
 * 其余（含未指定）      → 底层 ReAct
 * </pre>
 * <p>纯领域实现（零 Spring 依赖），由 start 层装配为 Bean。
 * {@code isWorkflowBizCode} / {@code isPlanTask} 为 protected 扩展点，业务可覆写注入自定义路由规则。</p>
 * <p>配置驱动（P9）：可选注入 {@link WorkflowRepository}，凡已为该 bizCode 注册工作流定义者，
 * 一律视为 Workflow 信号，使 config 注册的多阶段工作流无需依赖 bizCode 命名约定即可生效。</p>
 * <p><b>J8（插入点 ⑥，advisory）</b>：可选经 {@link DecisionPort} 接缝注入决策平面（其后端由 J3 装配、
 * 适配 {@code DecisionEngineSPI}/Jev，<b>不硬编具体实现进路由类</b>，C1）。高置信 {@code choice} 覆盖规则选择，
 * 低置信/缺失/异常一律回退既有确定性规则（P10/P12③）。<b>范围红线</b>：v3.0 阶段仅 advisory——
 * <b>不含任何"自修改/离线回放调优路由规则"逻辑（那属 R4/RSI，当前排除）</b>；advisory 无状态、不积累反馈。
 * 且对合规/审批边界<b>只收紧不放松</b>：规则判定为 WORKFLOW/HYBRID 时，advisory 不得降级到无审批的 REACT/PLAN（P12②）。</p>
 */
public class DefaultLayerRouter implements LayerRouter {

    /** 可选工作流仓储：存在定义即视为 workflow 信号（P9）；为 null 时仅按命名约定判定。 */
    private final WorkflowRepository workflowRepository;

    /** J8：可选决策平面端口（advisory）。缺省 {@code null} → 纯规则路由，行为与 v2.0 一致（P10/P12①）。 */
    private DecisionPort decisionPort;

    /** J8 路由 advisory 置信阈值；缺省 DD11（routing 0.75）。 */
    private DecisionThresholds decisionThresholds = DecisionThresholds.defaults();

    public DefaultLayerRouter() {
        this(null);
    }

    public DefaultLayerRouter(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    /**
     * J8：装配可选决策平面（advisory）。{@code null} 端口 → 保持纯规则路由（P10/P12①）；
     * {@code null} 阈值 → 沿用 DD11 缺省（routing 0.75）。
     */
    public void attachDecisionPlane(DecisionPort decisionPort, DecisionThresholds thresholds) {
        this.decisionPort = decisionPort;
        if (thresholds != null) {
            this.decisionThresholds = thresholds;
        }
    }

    @Override
    public RuntimeLayer route(String bizCode, String userMessage) {
        RuntimeLayer rule = ruleRoute(bizCode, userMessage);
        RuntimeLayer advisory = advisoryRoute(bizCode, userMessage, rule);
        return advisory != null ? advisory : rule;
    }

    /** 既有确定性规则路由（v2.0 行为）；J8 advisory 缺失/低置信/被合规守卫否决时的兜底。 */
    private RuntimeLayer ruleRoute(String bizCode, String userMessage) {
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

    /**
     * J8 advisory 路由：高置信 {@code choice} 覆盖规则。
     * <p>低置信（&lt; {@code routing} 阈值）/ 答案缺失 / 非 CHOICE / choice 不可识别 / 会放松合规边界 →
     * 返回 {@code null}，由 {@link #route} 回退规则（P10/P12②③）。<b>无状态</b>：不写回、不积累、不自修改规则（R4 排除）。</p>
     */
    private RuntimeLayer advisoryRoute(String bizCode, String userMessage, RuntimeLayer rule) {
        if (decisionPort == null) {
            return null;
        }
        DecisionAnswer answer = decideQuietly(routingState(bizCode, userMessage));
        if (answer == null || answer.type() != DecisionType.CHOICE || answer.choice() == null
                || answer.confidence() < decisionThresholds.routing()) {
            return null;
        }
        RuntimeLayer advised = mapChoiceToLayer(answer.choice());
        if (advised == null || relaxesCompliance(rule, advised)) {
            return null;
        }
        return advised;
    }

    /** 合规/审批边界（WORKFLOW/HYBRID）不得被 advisory 降级到无审批的 REACT/PLAN（P12②只收紧不放松）。 */
    private static boolean relaxesCompliance(RuntimeLayer rule, RuntimeLayer advised) {
        boolean ruleHasBoundary = rule == RuntimeLayer.WORKFLOW_LAYER || rule == RuntimeLayer.HYBRID_LAYER;
        boolean advisedLacksBoundary = advised == RuntimeLayer.REACT_LAYER || advised == RuntimeLayer.PLAN_LAYER;
        return ruleHasBoundary && advisedLacksBoundary;
    }

    /** 静默判定：异常/缺失一律返回 null（回退规则，P10），绝不让决策平面异常中断路由。 */
    private DecisionAnswer decideQuietly(String state) {
        try {
            DecisionResponse response = decisionPort.decide(DecisionRequest.of(state,
                    DecisionQuestion.choice("layer-route",
                            "为该任务选择最合适的执行层/范式：单轮推理或开放问答→react，多步骤需规划拆解→plan，"
                                    + "强合规/审批确定性阶段流程→workflow，强合规且多步骤→hybrid",
                            Map.of("react", "单轮推理/开放问答（底层 ReAct）",
                                    "plan", "多步骤复杂任务需规划拆解（中层 PlanAndExecute）",
                                    "workflow", "强合规/审批确定性阶段流程（顶层 Workflow）",
                                    "hybrid", "强合规且多步骤（分层混合 Hybrid）"))));
            return response == null ? null : response.answer("layer-route");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String routingState(String bizCode, String userMessage) {
        return "bizCode: " + (bizCode == null ? "" : bizCode)
                + "\n用户消息: " + truncate(userMessage, 1000);
    }

    private static RuntimeLayer mapChoiceToLayer(String choice) {
        return switch (choice.toLowerCase(Locale.ROOT)) {
            case "react", "re-act", "react_layer" -> RuntimeLayer.REACT_LAYER;
            case "plan", "plan-and-execute", "plan_and_execute", "plan_layer" -> RuntimeLayer.PLAN_LAYER;
            case "workflow", "workflow_layer" -> RuntimeLayer.WORKFLOW_LAYER;
            case "hybrid", "hybrid_layer" -> RuntimeLayer.HYBRID_LAYER;
            default -> null;
        };
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
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
