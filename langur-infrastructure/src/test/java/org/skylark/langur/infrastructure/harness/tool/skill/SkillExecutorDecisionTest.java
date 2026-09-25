package org.skylark.langur.infrastructure.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.infrastructure.skill.TranslatePrdSkill;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J10 验收 - {@link SkillExecutor} 的 {@code DECISION} 语义步骤（插入点 ⑨）与 {@link TranslatePrdSkill}
 * 质量门（插入点 ⑩）。
 * <p>⑨：DECISION 由 {@code DecisionPort} 求值（score/noul），值+置信均 ≥ 阈值 → onTrue，否则 onFalse；
 * 低置信 fail-closed（P12③）；与 CONDITION 并存；决策平面缺失/后端异常 → 降级为 CONDITION（若可表达）
 * 或默认放行（P10，绝不中断）；沿用步数硬上限防环。⑩：TranslatePrdSkill 的 draft 后 score 验收，
 * 低分触发<b>有界一次</b>重 draft，高分/缺判定后端直接产出草稿（等价 v2.0）。</p>
 */
class SkillExecutorDecisionTest {

    /** 桩调度器：echo 回显（DECISION 测试仅用于占位/分支落点）。 */
    private static final class StubDispatcher implements ToolDispatcher {
        @Override
        public ToolCallResult dispatch(ToolCallRequest request) {
            return ToolCallResult.success("echo:" + request.getArguments().get("v"), 1);
        }
    }

    /** 桩决策端口：恒返回指定 score 答案（key 由请求问题推断），并计数。 */
    private static final class StubDecisionPort implements DecisionPort {
        private final double value;
        private final double confidence;
        int calls = 0;

        StubDecisionPort(double value, double confidence) {
            this.value = value;
            this.confidence = confidence;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls++;
            String key = request.questions().get(0).key();
            return DecisionResponse.of(Map.of(key, DecisionAnswer.ofScore(value, confidence)));
        }
    }

    /** 桩决策端口：恒抛异常，验证 P10 后端故障降级。 */
    private static final class ThrowingDecisionPort implements DecisionPort {
        @Override
        public DecisionResponse decide(DecisionRequest request) {
            throw new IllegalStateException("decision backend down");
        }
    }

    /** 桩 LLM：按用户提示词标记区分 analyze/draft/redraft，并计数各步调用。 */
    private static final class StubLlmPort implements SkillLlmPort {
        int analyzeCalls = 0;
        int draftCalls = 0;
        int redraftCalls = 0;

        @Override
        public String complete(String modelRole, String systemPrompt, String userPrompt) {
            if (userPrompt != null && userPrompt.contains("改进后的完整 PRD")) {
                redraftCalls++;
                return "redrafted-prd";
            }
            if (userPrompt != null && userPrompt.contains("请产出 PRD")) {
                draftCalls++;
                return "draft-prd";
            }
            analyzeCalls++;
            return "analysis-text";
        }
    }

    private SkillExecutor executor(DecisionPort decisionPort) {
        return new SkillExecutor(new StubDispatcher(), new SkillExpressionResolver(new ObjectMapper()),
                null, null, null, decisionPort);
    }

    private SkillExecutor executor(SkillLlmPort llmPort, DecisionPort decisionPort) {
        return new SkillExecutor(new StubDispatcher(), new SkillExpressionResolver(new ObjectMapper()),
                llmPort, null, null, decisionPort);
    }

    private SkillSpec spec(List<SkillStep> steps) {
        return SkillSpec.builder().name("demo").description("d").steps(steps).build();
    }

    /**
     * 分支夹具：{@code good} 步在前设定 lastOutput，质量门 onTrue=END（保留 good 输出）、onFalse=bad（末步）。
     * 这样"真"分支 END 返回 echo:good，"假"分支落到 echo:bad，二者互不串扰。
     */
    private SkillSpec branchSpec(SkillStep gate) {
        return spec(List.of(
                SkillStep.toolCall("good", "tool:echo", Map.of("v", "good")),
                gate,
                SkillStep.toolCall("bad", "tool:echo", Map.of("v", "bad"))));
    }

    // ---------- ⑨ DECISION 步骤 ----------

    @Test
    void shouldRouteDecisionTrueWhenScoreAboveThreshold() {
        SkillSpec spec = branchSpec(SkillStep.decisionScore(
                "gate", "材料是否充分", 0.8, Map.of("m", "${input.x}"), "END", "bad"));

        String out = executor(new StubDecisionPort(0.9, 0.9)).execute(spec, Map.of("x", "ctx"));

        assertEquals("echo:good", out, "score 值+置信均达阈值 → onTrue(END) 保留 good 输出");
    }

    @Test
    void shouldRouteDecisionFalseWhenScoreBelowThreshold() {
        SkillSpec spec = branchSpec(SkillStep.decisionScore(
                "gate", "材料是否充分", 0.8, Map.of("m", "${input.x}"), "END", "bad"));

        String out = executor(new StubDecisionPort(0.4, 0.9)).execute(spec, Map.of("x", "ctx"));

        assertEquals("echo:bad", out, "score 未达阈值 → onFalse 分支");
    }

    @Test
    void shouldFailClosedOnLowConfidenceDecision() {
        SkillSpec spec = branchSpec(SkillStep.decisionScore(
                "gate", "材料是否充分", 0.8, Map.of(), "END", "bad"));

        // 值高达 0.99 但置信 0.3 < 0.8 → fail-closed 走 onFalse（P12③ 最保守分支）
        String out = executor(new StubDecisionPort(0.99, 0.3)).execute(spec, Map.of());

        assertEquals("echo:bad", out, "低置信 fail-closed → onFalse（P12③）");
    }

    @Test
    void shouldDegradeToDefaultPassWhenDecisionPortAbsent() {
        SkillSpec spec = branchSpec(SkillStep.decisionScore(
                "gate", "材料是否充分", 0.8, Map.of(), "END", "bad"));

        // 决策平面缺失且无 condition → 默认放行 onTrue=END（P10，等价 v2.0 无质量门），技能不中断
        String out = executor(null).execute(spec, Map.of());

        assertEquals("echo:good", out, "无判定后端 → 默认放行分支（P10）");
    }

    @Test
    void shouldDegradeDecisionToConditionWhenPortAbsentAndConditionPresent() {
        SkillStep gate = SkillStep.builder().id("gate").type(StepType.DECISION)
                .decisionKey("gate").decisionType("score").decisionInstructions("语义判定")
                .decisionThreshold(0.8)
                .condition("${input.ok} == 'yes'") // 可表达的确定性兜底
                .onTrue("END").onFalse("bad").outputVar("gate").build();
        SkillSpec spec = branchSpec(gate);

        // 决策平面缺失 → 降级为 CONDITION 按表达式求值（P10）
        assertEquals("echo:good", executor(null).execute(spec, Map.of("ok", "yes")));
        assertEquals("echo:bad", executor(null).execute(spec, Map.of("ok", "no")));
    }

    @Test
    void shouldDegradeWithoutInterruptionWhenDecisionBackendThrows() {
        SkillSpec spec = branchSpec(SkillStep.decisionScore(
                "gate", "材料是否充分", 0.8, Map.of(), "END", "bad"));

        // 后端异常 → P10 降级默认放行，技能不中断（区别于低置信 fail-closed）
        String out = executor(new ThrowingDecisionPort()).execute(spec, Map.of());

        assertEquals("echo:good", out, "后端故障降级放行，不中断技能（P10）");
    }

    @Test
    void shouldTripStepLimitOnRunawayDecisionLoop() {
        // onTrue 跳回自身且恒真（阈值 0）→ 构造死循环，应被全局步数硬上限拦截
        SkillSpec spec = spec(List.of(
                SkillStep.decisionScore("spin", "恒真判定", 0.0, Map.of(), "spin", null)));

        SkillExecutionException ex = assertThrows(SkillExecutionException.class,
                () -> executor(new StubDecisionPort(1.0, 1.0)).execute(spec, Map.of()));
        assertTrue(ex.getMessage().contains("step limit exceeded"));
    }

    // ---------- ⑩ TranslatePrdSkill 质量门 ----------

    private SkillSpec translatePrdSpec() {
        return SkillSpec.builder().name("translate-prd").description("d")
                .steps(new TranslatePrdSkill().steps()).build();
    }

    @Test
    void shouldOutputDraftDirectlyWhenPrdScoreAboveThreshold() {
        StubLlmPort llm = new StubLlmPort();
        String out = executor(llm, new StubDecisionPort(0.9, 0.9))
                .execute(translatePrdSpec(), Map.of("feature", "f", "codeContext", "c"));

        assertEquals("draft-prd", out, "高分 → 质量门 END，直接产出草稿");
        assertEquals(1, llm.draftCalls);
        assertEquals(0, llm.redraftCalls, "高分不触发重 draft");
    }

    @Test
    void shouldRedraftOnceWhenPrdScoreBelowThreshold() {
        StubLlmPort llm = new StubLlmPort();
        StubDecisionPort decisionPort = new StubDecisionPort(0.4, 0.9);
        String out = executor(llm, decisionPort)
                .execute(translatePrdSpec(), Map.of("feature", "f", "codeContext", "c"));

        assertEquals("redrafted-prd", out, "低分 → 触发有界重 draft 并产出改进稿");
        assertEquals(1, llm.draftCalls);
        assertEquals(1, llm.redraftCalls, "有界：恰好重 draft 一次（无第二次质量门）");
        assertEquals(1, decisionPort.calls, "质量门只判定一次，不因重 draft 再判");
    }

    @Test
    void shouldDegradeTranslatePrdToDraftWhenDecisionPortAbsent() {
        StubLlmPort llm = new StubLlmPort();
        String out = executor(llm, null)
                .execute(translatePrdSpec(), Map.of("feature", "f", "codeContext", "c"));

        // 决策平面缺失 → 质量门降级默认放行（END），等价 v2.0 直接产出草稿（P10）
        assertEquals("draft-prd", out);
        assertEquals(1, llm.draftCalls);
        assertEquals(0, llm.redraftCalls);
    }
}
