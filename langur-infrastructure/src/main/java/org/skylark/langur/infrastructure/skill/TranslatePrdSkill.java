package org.skylark.langur.infrastructure.skill;

import org.skylark.langur.infrastructure.harness.tool.skill.Skill;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillDef;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 转译 PRD 技能（Coding Agent 示例）- 以 {@code skill:translate-prd} 注册进 T 组件，
 * 把"代码上下文 + 目标特性"转译为结构化 PRD 文档。
 * <p>两步 LLM 编排：REASONING 角色先从代码上下文抽取功能/接口/约束的结构化分析，
 * ACTION 角色再据分析产出 Markdown PRD（背景/目标/范围/功能需求/接口/非功能/验收）。</p>
 * <p>J10⑩ 质量门：{@code draft} 后加一步 {@code DECISION}（{@code score} 完整性验收）——达标直接产出，
 * 低分触发<b>有界</b>（至多一次）重 draft 后产出，绝不无限重写（沿用 {@code SkillExecutor} 步数硬上限）。
 * 决策平面缺失时该门降级为默认放行（P10），行为等价 v2.0 直接产出草稿。</p>
 * <p>技能返回最后一步输出（PRD 正文），可由 Workflow 阶段记录为具名产物 {@code prd-document}。
 * 入参以 {@code ${input.x}} 引用，前序步骤输出以 {@code ${stepId}} 引用（{@code SkillExpressionResolver}）。</p>
 */
@Component
@SkillDef(name = "translate-prd",
        description = "Translate code context and a target feature into a structured PRD document",
        riskLevel = "LOW",
        timeoutSeconds = 180)
public class TranslatePrdSkill implements Skill {

    /** J10⑩：PRD 完整性验收阈值（score 值+置信均 ≥ 此值才直接产出，否则有界重 draft）。 */
    private static final double QUALITY_THRESHOLD = 0.8d;

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "feature", Map.of("type", "string",
                                "description", "The target feature/capability to specify"),
                        "codeContext", Map.of("type", "string",
                                "description", "Relevant code context (files, symbols, behavior) gathered from the repo")
                ),
                "required", List.of("feature", "codeContext")
        );
    }

    @Override
    public List<SkillStep> steps() {
        return List.of(
                SkillStep.llmCall("analyze", "REASONING",
                        "你是资深系统分析师。仅依据给定的代码上下文做客观分析，不臆造未出现的实现。",
                        "目标特性：${input.feature}\n\n代码上下文：\n${input.codeContext}\n\n"
                                + "请抽取并结构化输出：1) 现有相关功能与行为；2) 涉及的接口/数据结构/依赖；"
                                + "3) 约束与边界条件；4) 与目标特性相关的缺口。",
                        "analysis"),
                SkillStep.llmCall("draft", "ACTION",
                        "你是资深产品经理，输出规范的 Markdown PRD，不输出多余解释。",
                        "目标特性：${input.feature}\n\n结构化分析：\n${analysis}\n\n"
                                + "请产出 PRD，包含章节：背景与问题、目标与非目标、用户与场景、功能需求（含验收标准）、"
                                + "接口与数据、非功能需求（性能/安全/兼容）、风险与开放问题。",
                        "prd"),
                // J10⑩：语义质量门——score 判 PRD 完整性；达标 END 直接产出，低分跳 redraft（有界一次）
                SkillStep.decisionScore("quality-gate",
                        "评估这份 PRD 草稿的完整性与可执行性得分（0-1，越完整、验收标准越清晰越高）",
                        QUALITY_THRESHOLD,
                        Map.of("feature", "${input.feature}", "prd", "${prd}"),
                        "END", "redraft"),
                SkillStep.llmCall("redraft", "ACTION",
                        "你是资深产品经理。在保留原结构的前提下补全缺失章节、细化验收标准，输出改进后的完整 Markdown PRD。",
                        "目标特性：${input.feature}\n\n结构化分析：\n${analysis}\n\n"
                                + "当前 PRD 草稿（完整性评分 ${quality-gate}，未达阈值 "
                                + QUALITY_THRESHOLD + "）：\n${prd}\n\n"
                                + "请针对性补全薄弱章节并明确每条功能需求的验收标准，产出改进后的完整 PRD。",
                        "prd")
        );
    }
}
