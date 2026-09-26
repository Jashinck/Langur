package org.skylark.langur.domain.harness.rsi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 工具能力缺口检测器离线确定性测试（无 Mockito）。
 * <p>覆盖：缺口识别、已覆盖返回 empty、空任务/空关键词守卫。</p>
 */
class ToolGapDetectorTest {

    private final ToolGapDetector detector = new ToolGapDetector();

    @Test
    void shouldDetectGapWhenCapabilityMissing() {
        Optional<String> gap = detector.detectGap("用户需要发送邮件通知", List.of("搜索"), List.of("邮件", "搜索"));

        assertTrue(gap.isPresent());
        assertEquals("邮件", gap.get());
    }

    @Test
    void shouldReturnEmptyWhenCovered() {
        Optional<String> gap = detector.detectGap("需要搜索知识库", List.of("搜索"), List.of("搜索"));

        assertTrue(gap.isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankTaskOrNullKeywords() {
        assertTrue(detector.detectGap("   ", List.of("搜索"), List.of("搜索")).isEmpty());
        assertTrue(detector.detectGap(null, List.of("搜索"), List.of("搜索")).isEmpty());
        assertTrue(detector.detectGap("需要邮件", List.of("搜索"), null).isEmpty());
        assertTrue(detector.detectGap("需要邮件", List.of("搜索"), List.of()).isEmpty());
    }
}
