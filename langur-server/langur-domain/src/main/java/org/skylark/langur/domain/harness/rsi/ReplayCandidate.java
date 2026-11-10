package org.skylark.langur.domain.harness.rsi;

import org.skylark.langur.domain.harness.decision.DecisionThresholds;

import java.util.Map;

/**
 * 回放候选（R0）。描述一次反事实重放要注入的策略变更——阈值组覆盖、按判定 key 的强制路由，或生成级变更标记。
 * <p>纯 JDK record，零外部依赖（P1）。{@code thresholds} 为空表示沿用轨迹基线阈值；{@code forcedRoutes}
 * 为空表示全部按录制判定 + 阈值重算。构造时防御性拷贝为不可变。</p>
 *
 * @param candidateId  候选标识（审计/报告用）
 * @param kind         候选类型（决定回放的确定性能力，见 {@link CandidateKind}）
 * @param thresholds   覆盖阈值组（空 → 用 {@link Trajectory#baselineThresholds()}）
 * @param forcedRoutes 按判定 key 强制分流（空 → 按录制判定重算）
 */
public record ReplayCandidate(String candidateId,
                              CandidateKind kind,
                              DecisionThresholds thresholds,
                              Map<String, ReplayRoute> forcedRoutes) {

    public ReplayCandidate {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("ReplayCandidate candidateId must not be blank");
        }
        kind = (kind == null) ? CandidateKind.BASELINE : kind;
        forcedRoutes = (forcedRoutes == null) ? Map.of() : Map.copyOf(forcedRoutes);
    }

    /** 原策略候选（复现基线）。 */
    public static ReplayCandidate baseline(String candidateId) {
        return new ReplayCandidate(candidateId, CandidateKind.BASELINE, null, Map.of());
    }

    /** 阈值调优候选（R4 作用面）。 */
    public static ReplayCandidate ofThresholds(String candidateId, DecisionThresholds thresholds) {
        return new ReplayCandidate(candidateId, CandidateKind.THRESHOLD, thresholds, Map.of());
    }

    /** 强制路由候选（按判定 key 覆盖分流）。 */
    public static ReplayCandidate ofForcedRoutes(String candidateId, Map<String, ReplayRoute> forcedRoutes) {
        return new ReplayCandidate(candidateId, CandidateKind.ROUTE, null, forcedRoutes);
    }

    /** 生成级候选（PROMPT/SKILL/PARAMS）——离线重放录制响应，无生成级反事实信号。 */
    public static ReplayCandidate ofGenerative(String candidateId, CandidateKind kind) {
        return new ReplayCandidate(candidateId, kind, null, Map.of());
    }
}
