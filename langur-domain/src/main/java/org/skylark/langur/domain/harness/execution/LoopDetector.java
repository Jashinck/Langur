package org.skylark.langur.domain.harness.execution;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 循环检测器（§8.2）：滑动窗口记录最近 N 轮 action/observation 指纹，
 * 同一指纹重复达到阈值即判定陷入循环，触发强制终止（终止原因 LOOP_DETECTED）。
 * <p>纯领域实现，单次执行循环内串行使用（非线程安全）。</p>
 */
public class LoopDetector {

    private final int windowSize;
    private final int repeatThreshold;
    private final Deque<String> fingerprints = new ArrayDeque<>();

    public LoopDetector(int windowSize, int repeatThreshold) {
        this.windowSize = Math.max(1, windowSize);
        this.repeatThreshold = Math.max(2, repeatThreshold);
    }

    public static LoopDetector defaults() {
        return new LoopDetector(6, 3);
    }

    /**
     * 记录本轮指纹并判定是否陷入循环。
     *
     * @return true 表示窗口内同一指纹重复次数达到阈值
     */
    public boolean recordAndDetect(String action, String observation) {
        String fingerprint = fingerprint(action, observation);
        fingerprints.addLast(fingerprint);
        if (fingerprints.size() > windowSize) {
            fingerprints.removeFirst();
        }
        long repeats = fingerprints.stream().filter(fingerprint::equals).count();
        return repeats >= repeatThreshold;
    }

    private String fingerprint(String action, String observation) {
        return action + "::" + observation;
    }
}
