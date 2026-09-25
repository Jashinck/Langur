package org.skylark.langur.infrastructure.harness.decision;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.state.StateSnapshot;

/**
 * 决策轨迹录制通道缺省实现（J2）：把判定快照以 DEBUG 级落日志，作为 R0 落地前的过渡留痕。
 * <p>不带 {@code @Component}——由 J3 {@code DecisionConfiguration} 在 {@code langur.decision.record=true}
 * 时装配为录制通道；R0 落地后可替换为 JPA/回放级实现（同 {@link DecisionTrajectoryRecorder} 契约，P5 开闭）。
 * 失败静默降级（P10）。</p>
 */
@Slf4j
public class LoggingDecisionTrajectoryRecorder implements DecisionTrajectoryRecorder {

    @Override
    public void record(StateSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            log.debug("[DECISION-RECORD] snapshotId={} taskId={} latencyMillis={} answers={}",
                    snapshot.getSnapshotId(),
                    snapshot.getTaskId(),
                    snapshot.getPayload() == null ? null : snapshot.getPayload().get("latencyMillis"),
                    snapshot.getPayload() == null ? null : snapshot.getPayload().get("response"));
        } catch (RuntimeException e) {
            log.debug("[DECISION-RECORD] record failed, silently dropped", e);
        }
    }
}
