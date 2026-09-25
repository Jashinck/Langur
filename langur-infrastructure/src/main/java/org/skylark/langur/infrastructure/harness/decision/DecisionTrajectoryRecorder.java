package org.skylark.langur.infrastructure.harness.decision;

import org.skylark.langur.domain.harness.state.StateSnapshot;

/**
 * 决策轨迹录制通道（J2）。{@link RecordingDecisionPort} 在 {@code record=true} 时把每次判定的
 * request/response 封装为 S 组件 {@link StateSnapshot} 交由此端口落轨迹仓库。
 * <p><b>R0 前向兼容（C2/DD12）</b>：现在埋点录制，R0 回放引擎落地后即可直接重放录制的 Jev 判定，
 * 不重新联网 → 反事实重放确定。缺省不装配即不录制（P10），装饰链其余环节不受影响。</p>
 */
public interface DecisionTrajectoryRecorder {

    /**
     * 落一条决策轨迹快照。实现方须对失败静默降级（不抛出），避免录制反噬主链路（P10）。
     *
     * @param snapshot 载荷含 {@code request}/{@code response}/{@code latencyMillis} 的判定快照
     */
    void record(StateSnapshot snapshot);
}
