package org.skylark.langur.infrastructure.skill;

import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillDef;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillStep;
import org.skylark.langur.infrastructure.harness.tool.skill.StepType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转译 PRD 技能结构验收（Coding Agent）- {@link TranslatePrdSkill} 以 {@code skill:translate-prd} 注册，
 * analyze(REASONING)→draft(ACTION) 占位符串联，J10⑩ 在 draft 后加 {@code DECISION}（score 完整性）质量门，
 * 低分跳有界 redraft、高分 END 直接产出。入参 schema 要求 feature/codeContext。纯结构断言，不调用真实模型。
 */
class TranslatePrdSkillTest {

    private final TranslatePrdSkill skill = new TranslatePrdSkill();

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeclareSkillDefMetadata() {
        SkillDef def = TranslatePrdSkill.class.getAnnotation(SkillDef.class);
        assertNotNull(def, "须以 @SkillDef 标注为技能 Bean");
        assertEquals("translate-prd", def.name());
        assertEquals("LOW", def.riskLevel());
        assertTrue(def.timeoutSeconds() > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRequireFeatureAndCodeContextInputs() {
        Map<String, Object> schema = skill.inputSchema();
        assertEquals("object", schema.get("type"));
        Object required = schema.get("required");
        assertTrue(required instanceof List);
        List<String> requiredFields = (List<String>) required;
        assertTrue(requiredFields.contains("feature"));
        assertTrue(requiredFields.contains("codeContext"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("feature"));
        assertTrue(properties.containsKey("codeContext"));
    }

    @Test
    void shouldOrchestrateAnalyzeDraftQualityGateAndBoundedRedraft() {
        List<SkillStep> steps = skill.steps();
        assertEquals(4, steps.size(), "J10⑩：analyze→draft→质量门→有界 redraft");

        SkillStep analyze = steps.get(0);
        assertEquals("analyze", analyze.getId());
        assertEquals(StepType.LLM_CALL, analyze.getType());
        assertEquals("REASONING", analyze.getModelRole());
        assertEquals("analysis", analyze.getOutputVar());
        assertTrue(analyze.getUserPrompt().contains("${input.feature}"));
        assertTrue(analyze.getUserPrompt().contains("${input.codeContext}"));

        SkillStep draft = steps.get(1);
        assertEquals("draft", draft.getId());
        assertEquals(StepType.LLM_CALL, draft.getType());
        assertEquals("ACTION", draft.getModelRole());
        assertEquals("prd", draft.getOutputVar());
        assertTrue(draft.getUserPrompt().contains("${analysis}"), "draft 须引用 analyze 输出");
        assertTrue(draft.getUserPrompt().contains("${input.feature}"));

        // J10⑩：draft 后 score 质量门——达标 END 直接产出，低分跳 redraft（有界一次）
        SkillStep gate = steps.get(2);
        assertEquals("quality-gate", gate.getId());
        assertEquals(StepType.DECISION, gate.getType());
        assertEquals("score", gate.getDecisionType());
        assertTrue(gate.getDecisionThreshold() > 0d, "须设完整性验收阈值");
        assertEquals("END", gate.getOnTrue(), "达标直接产出草稿");
        assertEquals("redraft", gate.getOnFalse(), "低分触发有界重 draft");

        SkillStep redraft = steps.get(3);
        assertEquals("redraft", redraft.getId());
        assertEquals(StepType.LLM_CALL, redraft.getType());
        assertEquals("prd", redraft.getOutputVar());
        assertTrue(redraft.getUserPrompt().contains("${prd}"), "redraft 须引用草稿");
    }
}
