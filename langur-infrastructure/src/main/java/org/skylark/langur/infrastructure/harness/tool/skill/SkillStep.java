package org.skylark.langur.infrastructure.harness.tool.skill;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 技能步骤（§7.5 / H8）- 一条编排指令。按 {@link #getType()} 区分语义：
 * <ul>
 *   <li>{@code TOOL_CALL}：以 {@link #getToolId()} 调用工具，{@link #getArguments()} 支持
 *       {@code ${input.x}} / {@code ${stepId.field}} 占位符，结果写入 {@link #getOutputVar()}（缺省用步骤 id）。</li>
 *   <li>{@code CONDITION}：对 {@link #getCondition()} 求值，真跳 {@link #getOnTrue()}、假跳 {@link #getOnFalse()}；
 *       目标为 {@code null} 表示顺序执行下一步，{@code END} 表示结束技能。</li>
 *   <li>{@code DECISION}（J10⑨）：由 {@code DecisionPort} 求值 {@link #getDecisionInstructions()} 语义条件
 *       （{@link #getDecisionType()} = score/noul），值+置信均 ≥ {@link #getDecisionThreshold()} 跳 {@code onTrue}
 *       否则 {@code onFalse}；{@link #getArguments()} 为被判定材料。与 CONDITION 并存，决策平面缺失时降级（P10）。</li>
 *   <li>{@code LLM_CALL}：以 {@link #getModelRole()}（ACTION/REASONING）调模型，系统/用户提示词支持占位符，
 *       产出文本写入 {@link #getOutputVar()}。</li>
 *   <li>{@code LOOP}：当 {@link #getLoopCondition()} 为真时重复执行 {@link #getBody()}，受 {@link #getMaxIterations()}
 *       次数上限与全局步数硬上限双重约束。</li>
 *   <li>{@code PARALLEL}：并发执行 {@link #getBranches()} 各分支后汇聚，结果列表写入 {@link #getOutputVar()}。</li>
 *   <li>{@code SUB_WORKFLOW}：以 {@link #getSkillRef()} 嵌套调用另一技能（经 ToolDispatcher，保留四层校验链），
 *       {@link #getArguments()} 为入参。</li>
 *   <li>{@code SUB_AGENT}：委派 {@link #getAgentId()} 子 Agent 执行 {@link #getInstruction()}（为 H12 铺路）。</li>
 * </ul>
 */
@Getter
@Builder
public class SkillStep {

    /** 步骤标识，条件跳转与占位符引用以此定位。 */
    private final String id;
    private final StepType type;

    // ---- TOOL_CALL / SUB_WORKFLOW ----
    private final String toolId;
    private final Map<String, Object> arguments;
    private final String outputVar;

    // ---- CONDITION / LOOP ----
    private final String condition;
    private final String onTrue;
    private final String onFalse;

    // ---- DECISION (J10⑨) ----
    /** 判定问题 key（缺省用步骤 id）。 */
    private final String decisionKey;
    /** 判定类型：{@code score}（缺省）| {@code noul}（probability）。 */
    private final String decisionType;
    /** 语义判定指令（支持 {@code ${}} 占位符），如"这段代码上下文是否足以转译 PRD？"。 */
    private final String decisionInstructions;
    /** 值/置信阈值：二者均 ≥ 阈值才走 {@code onTrue}，否则 {@code onFalse}（低置信 fail-closed，P12③）。 */
    private final double decisionThreshold;

    // ---- LLM_CALL ----
    private final String modelRole;
    private final String systemPrompt;
    private final String userPrompt;

    // ---- LOOP ----
    private final String loopCondition;
    private final int maxIterations;
    private final List<SkillStep> body;

    // ---- PARALLEL ----
    private final List<List<SkillStep>> branches;

    // ---- SUB_WORKFLOW ----
    private final String skillRef;

    // ---- SUB_AGENT ----
    private final String agentId;
    private final String instruction;

    public static SkillStep toolCall(String id, String toolId, Map<String, Object> arguments) {
        return SkillStep.builder().id(id).type(StepType.TOOL_CALL)
                .toolId(toolId).arguments(arguments).outputVar(id).build();
    }

    public static SkillStep toolCall(String id, String toolId, Map<String, Object> arguments, String outputVar) {
        return SkillStep.builder().id(id).type(StepType.TOOL_CALL)
                .toolId(toolId).arguments(arguments).outputVar(outputVar).build();
    }

    public static SkillStep condition(String id, String condition, String onTrue, String onFalse) {
        return SkillStep.builder().id(id).type(StepType.CONDITION)
                .condition(condition).onTrue(onTrue).onFalse(onFalse).build();
    }

    /**
     * J10⑨：语义决策步骤（score 型）。{@code arguments} 承载被判定材料（经占位符解析后渲染进 state），
     * {@code instructions} 为判定指令；值+置信均 ≥ {@code threshold} → {@code onTrue}，否则 {@code onFalse}。
     */
    public static SkillStep decisionScore(String id, String instructions, double threshold,
                                          Map<String, Object> arguments, String onTrue, String onFalse) {
        return SkillStep.builder().id(id).type(StepType.DECISION)
                .decisionKey(id).decisionType("score").decisionInstructions(instructions)
                .decisionThreshold(threshold).arguments(arguments)
                .onTrue(onTrue).onFalse(onFalse).outputVar(id).build();
    }

    /** J10⑨：语义决策步骤（noul/probability 型，布尔式语义判定）。 */
    public static SkillStep decisionNoul(String id, String instructions, double threshold,
                                         Map<String, Object> arguments, String onTrue, String onFalse) {
        return SkillStep.builder().id(id).type(StepType.DECISION)
                .decisionKey(id).decisionType("noul").decisionInstructions(instructions)
                .decisionThreshold(threshold).arguments(arguments)
                .onTrue(onTrue).onFalse(onFalse).outputVar(id).build();
    }

    public static SkillStep llmCall(String id, String modelRole, String systemPrompt, String userPrompt) {
        return SkillStep.builder().id(id).type(StepType.LLM_CALL)
                .modelRole(modelRole).systemPrompt(systemPrompt).userPrompt(userPrompt).outputVar(id).build();
    }

    public static SkillStep llmCall(String id, String modelRole, String systemPrompt, String userPrompt,
                                    String outputVar) {
        return SkillStep.builder().id(id).type(StepType.LLM_CALL)
                .modelRole(modelRole).systemPrompt(systemPrompt).userPrompt(userPrompt).outputVar(outputVar).build();
    }

    public static SkillStep loop(String id, String loopCondition, int maxIterations, List<SkillStep> body) {
        return SkillStep.builder().id(id).type(StepType.LOOP)
                .loopCondition(loopCondition).maxIterations(maxIterations).body(body).outputVar(id).build();
    }

    public static SkillStep parallel(String id, List<List<SkillStep>> branches) {
        return SkillStep.builder().id(id).type(StepType.PARALLEL)
                .branches(branches).outputVar(id).build();
    }

    public static SkillStep parallel(String id, List<List<SkillStep>> branches, String outputVar) {
        return SkillStep.builder().id(id).type(StepType.PARALLEL)
                .branches(branches).outputVar(outputVar).build();
    }

    public static SkillStep subWorkflow(String id, String skillRef, Map<String, Object> arguments) {
        return SkillStep.builder().id(id).type(StepType.SUB_WORKFLOW)
                .skillRef(skillRef).arguments(arguments).outputVar(id).build();
    }

    public static SkillStep subWorkflow(String id, String skillRef, Map<String, Object> arguments, String outputVar) {
        return SkillStep.builder().id(id).type(StepType.SUB_WORKFLOW)
                .skillRef(skillRef).arguments(arguments).outputVar(outputVar).build();
    }

    public static SkillStep subAgent(String id, String agentId, String instruction) {
        return SkillStep.builder().id(id).type(StepType.SUB_AGENT)
                .agentId(agentId).instruction(instruction).outputVar(id).build();
    }

    public static SkillStep subAgent(String id, String agentId, String instruction, String outputVar) {
        return SkillStep.builder().id(id).type(StepType.SUB_AGENT)
                .agentId(agentId).instruction(instruction).outputVar(outputVar).build();
    }
}
