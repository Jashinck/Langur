package org.skylark.langur.infrastructure.harness.rsi;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.skylark.langur.domain.harness.rsi.DistillKind;
import org.skylark.langur.domain.harness.rsi.DistilledMemory;
import org.skylark.langur.domain.harness.rsi.RecordedDecision;
import org.skylark.langur.domain.harness.rsi.ReplayRoute;
import org.skylark.langur.domain.harness.rsi.ThresholdCategory;
import org.skylark.langur.domain.harness.rsi.Trajectory;
import org.skylark.langur.domain.harness.rsi.TrajectoryStep;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 M5/M6 大模型蒸馏抽取器离线测试（匿名桩 LlmGateway，无 Mockito）。
 * <p>覆盖：LLM 归纳成功轨迹 → 成功模式、LLM 异常/空响应 → 回退模板（P10）、空轨迹返回空、空网关回退。</p>
 */
class LlmDistillationExtractorTest {

    private static Trajectory trajectory(boolean success) {
        return Trajectory.of("task-1", success, DecisionThresholds.defaults(),
                List.of(TrajectoryStep.of(1, "search", 100, 10)),
                List.of(new RecordedDecision("d1", 1, DecisionAnswer.ofProbability(0.9, 0.9),
                        ThresholdCategory.ROUTING, ReplayRoute.RUN, 5L)));
    }

    private LlmGateway gateway(String result) {
        return new LlmGateway(null, null) {
            @Override
            public String complete(ModelRole role, String systemPrompt, String userMessage) {
                return result;
            }
        };
    }

    @Test
    void shouldDistillLlmSummaryAsSuccessPattern() {
        LlmDistillationExtractor extractor = new LlmDistillationExtractor(gateway("优先查证后作答"));

        List<DistilledMemory> memories = extractor.extract(trajectory(true));

        assertEquals(1, memories.size());
        assertEquals("优先查证后作答", memories.get(0).content());
        assertEquals(DistillKind.SUCCESS_PATTERN, memories.get(0).kind());
        assertEquals("task-1", memories.get(0).sourceTaskId());
    }

    @Test
    void shouldFallbackToTemplateOnLlmFailure() {
        LlmDistillationExtractor extractor = new LlmDistillationExtractor(new LlmGateway(null, null) {
            @Override
            public String complete(ModelRole role, String systemPrompt, String userMessage) {
                throw new IllegalStateException("llm down");
            }
        });

        List<DistilledMemory> memories = extractor.extract(trajectory(true));

        assertEquals(1, memories.size());
        assertTrue(memories.get(0).content().contains("成功模式"), "LLM 失败应回退模板");
    }

    @Test
    void shouldFallbackToTemplateOnBlankSummary() {
        LlmDistillationExtractor extractor = new LlmDistillationExtractor(gateway("   "));

        List<DistilledMemory> memories = extractor.extract(trajectory(false));

        assertEquals(1, memories.size());
        assertTrue(memories.get(0).content().contains("失败教训"), "空响应应回退模板");
    }

    @Test
    void shouldReturnEmptyForEmptyTrajectory() {
        LlmDistillationExtractor extractor = new LlmDistillationExtractor(gateway("x"));
        Trajectory empty = Trajectory.of("t", true, DecisionThresholds.defaults(), List.of(), List.of());

        assertTrue(extractor.extract(empty).isEmpty());
    }

    @Test
    void shouldFallbackWhenGatewayNull() {
        LlmDistillationExtractor extractor = new LlmDistillationExtractor(null);

        List<DistilledMemory> memories = extractor.extract(trajectory(true));

        assertEquals(1, memories.size());
        assertTrue(memories.get(0).content().contains("成功模式"));
    }
}
