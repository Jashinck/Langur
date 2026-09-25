package org.skylark.langur.domain.harness.rsi;

import java.util.Map;

/**
 * 基线对比报告（R0）。候选重放 vs 原策略重放的指标差与裁定——RSI 一切自改进的安全闸门（P11）：
 * <b>劣化即拒绝</b>，候选默认只是"提案"，绝不因回放通过而直接生效（生效须经 R-G 灰度 + 高危人审）。
 * <p>纯 JDK record，零外部依赖（P1）。裁定规则见 {@link ReplayEngine#compare}。</p>
 *
 * @param candidateId 候选标识
 * @param baseline    原策略重放指标
 * @param candidate   候选重放指标
 * @param verdict     裁定（IMPROVED/NEUTRAL/DEGRADED）
 * @param deltas      指标差（candidate − baseline）：rounds/tokens/latencyMillis/interceptions
 * @param rejected    是否拒绝（{@code verdict==DEGRADED} 或触犯只收紧红线 → true）
 * @param reason      裁定理由（审计/报告可读）
 */
public record BaselineComparison(String candidateId,
                                 ReplayMetrics baseline,
                                 ReplayMetrics candidate,
                                 Verdict verdict,
                                 Map<String, Long> deltas,
                                 boolean rejected,
                                 String reason) {

    public BaselineComparison {
        deltas = (deltas == null) ? Map.of() : Map.copyOf(deltas);
    }

    /** 回放裁定。 */
    public enum Verdict {
        /** 候选在不劣化成功/安全的前提下降低了成本（轮次/Token/延迟）。 */
        IMPROVED,
        /** 候选与基线无可测差异（如生成级候选离线重放录制响应）。 */
        NEUTRAL,
        /** 候选劣化（丢失成功 / 抬高成本 / 放松安全闸门）——拒绝。 */
        DEGRADED
    }
}
