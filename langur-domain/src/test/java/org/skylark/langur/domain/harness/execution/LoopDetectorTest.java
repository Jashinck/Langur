package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7 验收（单元）- 循环检测器：窗口内同一指纹重复达阈值触发。
 */
class LoopDetectorTest {

    @Test
    void shouldDetectWhenSameFingerprintRepeatsToThreshold() {
        LoopDetector detector = new LoopDetector(6, 3);
        assertFalse(detector.recordAndDetect("search", "no result"));
        assertFalse(detector.recordAndDetect("search", "no result"));
        assertTrue(detector.recordAndDetect("search", "no result"), "第三次重复应判定为循环");
    }

    @Test
    void shouldNotDetectWhenObservationsDiffer() {
        LoopDetector detector = new LoopDetector(6, 3);
        assertFalse(detector.recordAndDetect("search", "a"));
        assertFalse(detector.recordAndDetect("search", "b"));
        assertFalse(detector.recordAndDetect("search", "c"));
    }

    @Test
    void shouldSlideWindowAndForgetOldFingerprints() {
        LoopDetector detector = new LoopDetector(2, 3);
        detector.recordAndDetect("search", "x");
        detector.recordAndDetect("other", "y");
        detector.recordAndDetect("other", "z");
        // 窗口仅保留最近 2 条，最早的 x 已被淘汰，无法达到阈值 3
        assertFalse(detector.recordAndDetect("search", "x"));
    }
}
