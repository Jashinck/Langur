package org.skylark.langur.domain.harness.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * T12 验收 - 审计校验和：SHA-256 确定性、区分性、null 归一化（支撑不可篡改溯源）。
 */
class ChecksumsTest {

    @Test
    void shouldBeDeterministicAnd64HexChars() {
        String first = Checksums.sha256("trace-1", "actor", "TOOL_INVOCATION", "detail");
        String second = Checksums.sha256("trace-1", "actor", "TOOL_INVOCATION", "detail");
        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void shouldDifferWhenAnyFieldChanges() {
        assertNotEquals(
                Checksums.sha256("trace-1", "actor", "ACTION", "detail"),
                Checksums.sha256("trace-1", "actor", "ACTION", "tampered"));
    }

    @Test
    void shouldTreatNullAsEmpty() {
        assertEquals(Checksums.sha256(""), Checksums.sha256((String) null));
    }
}
