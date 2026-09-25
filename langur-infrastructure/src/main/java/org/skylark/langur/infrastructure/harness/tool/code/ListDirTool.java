package org.skylark.langur.infrastructure.harness.tool.code;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 目录列举工具（Coding Agent）- {@code list_dir}：列出白名单仓库内某目录的直接子项（目录以 {@code /} 标记）。
 * <p>路径经 {@link CodeAccessGuard} 限制在 roots 内；命中排除目录（.git/target 等）的子项不展示。</p>
 */
@Slf4j
public class ListDirTool extends Tool {

    private static final ToolDefinition DEFINITION = ToolDefinition.of(
            "list_dir",
            "List the immediate entries of a directory inside an allowed repository root",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of("type", "string", "description", "Directory path relative to a repo root, or absolute inside a root")
                    ),
                    "required", new String[]{"path"}
            )
    );

    private final CodeAccessGuard guard;

    public ListDirTool(CodeAccessGuard guard) {
        super(DEFINITION);
        this.guard = guard;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Path dir = guard.resolve(parameters.get("path") == null ? null : String.valueOf(parameters.get("path")));
            if (!Files.isDirectory(dir)) {
                return ToolResult.failure("not a directory: " + dir.getFileName());
            }
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> entries = Files.list(dir)) {
                entries.sorted().forEach(entry -> {
                    if (guard.isExcluded(entry)) {
                        return;
                    }
                    sb.append(Files.isDirectory(entry)
                            ? entry.getFileName() + "/"
                            : entry.getFileName().toString()).append('\n');
                });
            }
            String listing = sb.toString().trim();
            return ToolResult.success(listing.isEmpty() ? "(empty directory)" : listing);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("list_dir failed", e);
            return ToolResult.failure(e.getMessage());
        }
    }
}
