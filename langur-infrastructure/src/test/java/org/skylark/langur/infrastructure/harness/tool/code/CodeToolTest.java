package org.skylark.langur.infrastructure.harness.tool.code;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码访问工具功能验收（Coding Agent）- read_file/list_dir/grep 在真实临时仓库内工作，
 * 路径受 {@link CodeAccessGuard} 约束；{@link CodeToolProvider} 未启用时降级为空、启用后暴露三工具（P10）。
 */
class CodeToolTest {

    @TempDir
    Path repo;

    private CodeAccessGuard guard;
    private ReadFileTool readFile;
    private ListDirTool listDir;
    private GrepTool grep;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("src/Hello.java"),
                "public class Hello {\n    // say hi\n    void hi() {}\n}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), "# demo repo\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("target/generated.txt"), "should be excluded\n", StandardCharsets.UTF_8);

        CodeAccessProperties props = new CodeAccessProperties();
        props.setEnabled(true);
        props.setRoots(new ArrayList<>(List.of(repo.toString())));
        props.setMaxFileBytes(262_144L);
        props.setMaxResults(200);
        guard = new CodeAccessGuard(props);
        readFile = new ReadFileTool(guard, props.getMaxFileBytes());
        listDir = new ListDirTool(guard);
        grep = new GrepTool(guard, props.getMaxResults(), props.getMaxFileBytes());
    }

    @Test
    void shouldReadFileContent() {
        ToolResult result = readFile.execute(Map.of("path", "src/Hello.java"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("public class Hello"));
    }

    @Test
    void shouldReadFileLineRange() {
        ToolResult result = readFile.execute(Map.of("path", "src/Hello.java", "startLine", 2, "endLine", 3));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("say hi"));
        assertTrue(result.getContent().contains("2\t"), "行区间应带行号前缀");
        assertFalse(result.getContent().contains("public class Hello"), "区间外行不应出现");
    }

    @Test
    void shouldFailReadOnMissingOrEscapingFile() {
        assertFalse(readFile.execute(Map.of("path", "src/Nope.java")).isSuccess());
        assertFalse(readFile.execute(Map.of("path", "../../etc/passwd")).isSuccess());
        assertFalse(readFile.execute(new HashMap<>()).isSuccess(), "缺 path 须失败");
    }

    @Test
    void shouldListDirectoryAndSkipExcluded() {
        ToolResult result = listDir.execute(Map.of("path", "."));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("README.md"));
        assertTrue(result.getContent().contains("src/"), "目录以 / 标记");
        assertFalse(result.getContent().contains("target"), "排除目录不应展示");
    }

    @Test
    void shouldGrepMatchingLines() {
        ToolResult result = grep.execute(Map.of("pattern", "class Hello", "glob", ".*\\.java"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("Hello.java:1:"), "应含相对路径:行号");
        assertTrue(result.getContent().contains("public class Hello"));
    }

    @Test
    void shouldGrepReturnNoMatchAndRejectBadRegex() {
        ToolResult none = grep.execute(Map.of("pattern", "nothing_matches_this_token"));
        assertTrue(none.isSuccess());
        assertTrue(none.getContent().contains("(no matches)"));

        assertFalse(grep.execute(Map.of("pattern", "[")).isSuccess(), "非法正则须失败");
        assertFalse(grep.execute(new HashMap<>()).isSuccess(), "缺 pattern 须失败");
    }

    @Test
    void shouldNotGrepInsideExcludedDirectory() {
        ToolResult result = grep.execute(Map.of("pattern", "excluded"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("(no matches)"), "排除目录内命中不应返回");
    }

    @Test
    void providerShouldExposeToolsOnlyWhenEnabled() {
        CodeAccessProperties disabled = new CodeAccessProperties();
        disabled.setEnabled(false);
        assertTrue(new CodeToolProvider(disabled).getTools().isEmpty(), "未启用降级为空（P10）");

        CodeAccessProperties enabled = new CodeAccessProperties();
        enabled.setEnabled(true);
        enabled.setRoots(new ArrayList<>(List.of(repo.toString())));
        List<Tool> tools = new CodeToolProvider(enabled).getTools();
        assertEquals(3, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("read_file")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("list_dir")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("grep")));
    }
}
