package org.skylark.langur.infrastructure.harness.tool.code;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 代码检索工具（Coding Agent）- {@code grep}：在白名单仓库内按正则逐行检索，返回 {@code 相对路径:行号: 内容}。
 * <p>有界遍历：命中 {@code maxResults} 即终止（TERMINATE），跳过排除目录（.git/target 等）与超大/不可读文件，
 * 防大仓遍历失控。未给 path 时检索全部 roots。</p>
 */
@Slf4j
public class GrepTool extends Tool {

    private static final ToolDefinition DEFINITION = ToolDefinition.of(
            "grep",
            "Search file contents by regex within allowed repository roots; returns path:line: text matches",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "pattern", Map.of("type", "string", "description", "Regular expression to search for"),
                            "path", Map.of("type", "string", "description", "Optional directory/file to scope the search (defaults to all roots)"),
                            "glob", Map.of("type", "string", "description", "Optional filename regex filter, e.g. .*\\.java"),
                            "ignoreCase", Map.of("type", "boolean", "description", "Case-insensitive match (default false)")
                    ),
                    "required", new String[]{"pattern"}
            )
    );

    private final CodeAccessGuard guard;
    private final int maxResults;
    private final long maxFileBytes;

    public GrepTool(CodeAccessGuard guard, int maxResults, long maxFileBytes) {
        super(DEFINITION);
        this.guard = guard;
        this.maxResults = maxResults;
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String rawPattern = parameters.get("pattern") == null ? null : String.valueOf(parameters.get("pattern"));
        if (rawPattern == null || rawPattern.isBlank()) {
            return ToolResult.failure("pattern is required");
        }
        boolean ignoreCase = Boolean.parseBoolean(String.valueOf(parameters.getOrDefault("ignoreCase", "false")));
        Pattern pattern;
        try {
            pattern = Pattern.compile(rawPattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure("invalid regex: " + e.getDescription());
        }
        Pattern glob = compileGlob(parameters.get("glob"));

        try {
            List<Path> bases = resolveBases(parameters.get("path"));
            StringBuilder sb = new StringBuilder();
            int[] count = {0};
            for (Path base : bases) {
                search(base, base, pattern, glob, sb, count);
                if (count[0] >= maxResults) {
                    break;
                }
            }
            if (count[0] == 0) {
                return ToolResult.success("(no matches)");
            }
            String suffix = count[0] >= maxResults ? "\n... [capped at " + maxResults + " matches]" : "";
            return ToolResult.success(sb.toString().trim() + suffix);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("grep failed", e);
            return ToolResult.failure(e.getMessage());
        }
    }

    private List<Path> resolveBases(Object pathParam) {
        if (pathParam != null && !String.valueOf(pathParam).isBlank()) {
            return List.of(guard.resolve(String.valueOf(pathParam)));
        }
        return guard.roots();
    }

    private Pattern compileGlob(Object globParam) {
        if (globParam == null || String.valueOf(globParam).isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(String.valueOf(globParam));
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private void search(Path base, Path start, Pattern pattern, Pattern glob,
                        StringBuilder sb, int[] count) throws IOException {
        if (Files.isRegularFile(start)) {
            matchFile(base, start, pattern, glob, sb, count);
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(start) && guard.isExcluded(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (count[0] >= maxResults) {
                    return FileVisitResult.TERMINATE;
                }
                if (!attrs.isRegularFile() || (glob != null && !glob.matcher(file.getFileName().toString()).matches())) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > maxFileBytes) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    matchFile(base, file, pattern, glob, sb, count);
                } catch (IOException ignored) {
                    // 跳过不可读/二进制文件
                }
                return count[0] >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void matchFile(Path base, Path file, Pattern pattern, Pattern glob,
                           StringBuilder sb, int[] count) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String relative = relativize(base, file);
        for (int i = 0; i < lines.size() && count[0] < maxResults; i++) {
            String line = lines.get(i);
            if (pattern.matcher(line).find()) {
                sb.append(relative).append(':').append(i + 1).append(": ")
                        .append(line.strip()).append('\n');
                count[0]++;
            }
        }
    }

    private String relativize(Path base, Path file) {
        try {
            return base.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}
