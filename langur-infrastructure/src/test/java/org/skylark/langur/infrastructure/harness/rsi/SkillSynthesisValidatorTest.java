package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.harness.tool.skill.SkillStep;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3 技能合成校验器离线确定性测试（无 Mockito）。
 * <p>覆盖：合法候选放行、空候选拒绝、越权工具拒绝（P11 红线）、劣化（llmRounds>baseline）拒绝、空白名单不放行任何工具。</p>
 */
class SkillSynthesisValidatorTest {

    private final SkillSynthesisValidator validator = new SkillSynthesisValidator();

    private static SkillSynthesisCandidate candidate(String... toolIds) {
        List<SkillStep> steps = java.util.Arrays.stream(toolIds)
                .map(t -> SkillStep.toolCall("s-" + t, t, Map.of()))
                .toList();
        return SkillSynthesisCandidate.of("skill", "desc", "", "LOW", steps,
                List.of("t1"), 1, 3);
    }

    @Test
    void shouldAcceptCandidateWithAllowedToolsAndNoCostRegression() {
        SkillSynthesisVerdict verdict = validator.validate(
                candidate("search", "answer"), Set.of("search", "answer"));

        assertTrue(verdict.isAccepted(), verdict.toString());
    }

    @Test
    void shouldRejectEmptyCandidate() {
        SkillSynthesisVerdict verdict = validator.validate(null, Set.of("search"));
        assertFalse(verdict.isAccepted());
        assertTrue(verdict.detail().contains("empty"));

        verdict = validator.validate(SkillSynthesisCandidate.of("x", "", "", "LOW", List.of(), List.of(), 0, 0),
                Set.of("search"));
        assertFalse(verdict.isAccepted());
        assertTrue(verdict.detail().contains("empty"));
    }

    @Test
    void shouldRejectUnauthorizedTool() {
        SkillSynthesisVerdict verdict = validator.validate(
                candidate("search", "sudo-rm"), Set.of("search", "answer"));

        assertFalse(verdict.isAccepted());
        assertTrue(verdict.detail().contains("sudo-rm"), verdict.detail());
        assertTrue(verdict.reason() == SkillSynthesisVerdict.Reason.REJECTED_UNAUTHORIZED_TOOL);
    }

    @Test
    void shouldRejectDegradedCandidate() {
        // 候选语义判定轮次 5 > 基线 3 → 劣化
        SkillSynthesisCandidate degraded = SkillSynthesisCandidate.of(
                "skill", "", "", "LOW",
                List.of(SkillStep.toolCall("s1", "search", Map.of())),
                List.of("t1"), 5, 3);

        SkillSynthesisVerdict verdict = validator.validate(degraded, Set.of("search"));
        assertFalse(verdict.isAccepted());
        assertTrue(verdict.reason() == SkillSynthesisVerdict.Reason.REJECTED_DEGRADED);
    }

    @Test
    void shouldNotAllowAnyToolWhenAllowlistEmpty() {
        SkillSynthesisVerdict verdict = validator.validate(candidate("search"), Set.of());
        assertFalse(verdict.isAccepted(), "空白名单不放行任何外部工具（P11 缺省收紧）");
    }
}
