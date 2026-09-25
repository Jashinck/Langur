package org.skylark.langur.domain.harness.workflow;

import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.harness.decision.DecisionType;

import java.util.Map;

/**
 * J4 - Workflow 阶段决策闸门定义（插入点 ①，v3.0 §3.1，配置驱动 P9）。
 * <p>{@link WorkflowStage} 的可选附件：一个判定问题（{@link DecisionQuestion} 要素）+ 置信阈值 +
 * 分支目标阶段。阶段产出后经 {@code DecisionPort} 判定走向（run-next/skip/branch-to-stage/abort/
 * require-approval，见 {@link GateRoute}）；未配置或低置信/后端缺失 → 走默认固定顺序（P10），
 * 与 v2.0 行为一致。纯值对象（P1）。</p>
 *
 * @param key           判定问题键（答案映射用，必填）
 * @param type          判定类型（CHOICE 分流 / PROBABILITY 廉价预判，缺省 CHOICE）
 * @param instructions  判定指令（问题语义，必填）
 * @param criteria      CHOICE 选项 → 判据说明（可空）
 * @param threshold     置信阈值（&le;0 → {@link DecisionThresholds#DEFAULT_ROUTING}）
 * @param branchStageId BRANCH 动作的目标阶段 id（可空 → BRANCH 视作 RUN_NEXT）
 */
public record StageDecisionGate(String key,
                                DecisionType type,
                                String instructions,
                                Map<String, String> criteria,
                                double threshold,
                                String branchStageId) {

    public StageDecisionGate {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("StageDecisionGate key must not be blank");
        }
        type = (type == null) ? DecisionType.CHOICE : type;
        instructions = (instructions == null) ? "" : instructions;
        criteria = (criteria == null) ? Map.of() : Map.copyOf(criteria);
        threshold = (threshold <= 0) ? DecisionThresholds.DEFAULT_ROUTING : threshold;
    }

    /** CHOICE 型闸门（程序化分流的主形态）。 */
    public static StageDecisionGate choice(String key, String instructions, Map<String, String> criteria,
                                           double threshold, String branchStageId) {
        return new StageDecisionGate(key, DecisionType.CHOICE, instructions, criteria, threshold, branchStageId);
    }

    /** PROBABILITY（noul）型闸门：廉价布尔预判，高置信真 → RUN_NEXT，其余兜底默认顺序。 */
    public static StageDecisionGate probability(String key, String instructions, double threshold) {
        return new StageDecisionGate(key, DecisionType.PROBABILITY, instructions, Map.of(), threshold, null);
    }

    /** 转为发往决策平面的判定问题。 */
    public DecisionQuestion toQuestion() {
        return new DecisionQuestion(key, type, instructions, criteria);
    }
}
