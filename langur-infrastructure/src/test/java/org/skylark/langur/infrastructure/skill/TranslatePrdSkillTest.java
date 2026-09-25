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
 * 两步 LLM_CALL（REASONING→ACTION）占位符串联，入参 schema 要求 feature/codeContext。
 * 纯结构断言，不调用真实模型。
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
    void shouldOrchestrateTwoLlmStepsReasoningThenAction() {
        List<SkillStep> steps = skill.steps();
        assertEquals(2, steps.size());

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
        assertTrue(draft.getUserPrompt().contains("${analysis}"), "第二步须引用第一步输出");
        assertTrue(draft.getUserPrompt().contains("${input.feature}"));
    }
}
