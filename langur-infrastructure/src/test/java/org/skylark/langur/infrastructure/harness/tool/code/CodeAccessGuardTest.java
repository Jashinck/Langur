package org.skylark.langur.infrastructure.harness.tool.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码访问安全护栏验收（Coding Agent）- {@link CodeAccessGuard} 把路径限制在 roots 白名单内：
 * 空路径/遍历/绝对路径越权/命中排除目录一律拒绝；启用且配置 roots 时才 available（P10 降级）。
 * 纯 JDK、离线单测。
 */
class CodeAccessGuardTest {

    @TempDir
    Path tempDir;

    private CodeAccessProperties props(boolean enabled, List<String> roots) {
        CodeAccessProperties p = new CodeAccessProperties();
        p.setEnabled(enabled);
        p.setRoots(new ArrayList<>(roots));
        return p;
    }

    @Test
    void shouldBeAvailableOnlyWhenEnabledWithRoots() {
        assertTrue(new CodeAccessGuard(props(true, List.of(tempDir.toString()))).available());
        assertFalse(new CodeAccessGuard(props(false, List.of(tempDir.toString()))).available(),
                "未启用即不可用");
        assertFalse(new CodeAccessGuard(props(true, List.of())).available(),
                "启用但无 roots 亦不可用（P10）");
    }

    @Test
    void shouldResolveRelativePathInsideRoot() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        Path resolved = guard.resolve("src/Main.java");
        assertTrue(resolved.isAbsolute());
        assertTrue(resolved.startsWith(guard.roots().get(0)), "解析结果须落在 root 内");
        assertTrue(resolved.toString().endsWith("src/Main.java"));
    }

    @Test
    void shouldRejectBlankPath() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("  "));
    }

    @Test
    void shouldRejectTraversalEscape() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("../../etc/passwd"),
                "../ 遍历越权须拒绝");
    }

    @Test
    void shouldRejectAbsolutePathOutsideRoot() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("/etc/passwd"),
                "白名单外的绝对路径须拒绝");
    }

    @Test
    void shouldRejectExcludedDirectory() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("target/out.class"),
                "命中 excludeDirs 的路径须拒绝");
    }

    @Test
    void shouldDetectExcludedSegments() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        assertTrue(guard.isExcluded(Paths.get("/repo/.git/config")));
        assertTrue(guard.isExcluded(Paths.get("/repo/node_modules/x/index.js")));
        assertFalse(guard.isExcluded(Paths.get("/repo/src/Main.java")));
        assertFalse(guard.isExcluded(null));
    }

    @Test
    void shouldNormalizeRootsToAbsolute() {
        CodeAccessGuard guard = new CodeAccessGuard(props(true, List.of(tempDir.toString())));
        assertEquals(1, guard.roots().size());
        assertTrue(guard.roots().get(0).isAbsolute());
    }
}
