package org.skylark.langur.infrastructure.harness.tool.code;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 代码读取工具（Coding Agent）- {@code read_file}：读取白名单仓库内的文本文件，支持按行区间切片。
 * <p>路径经 {@link CodeAccessGuard} 限制在 roots 内；超过 {@code maxFileBytes} 截断，防大文件撑爆上下文。</p>
 */
@Slf4j
public class ReadFileTool extends Tool {

    private static final ToolDefinition DEFINITION = ToolDefinition.of(
            "read_file",
            "Read a text file (optionally a line range) from an allowed repository root",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of("type", "string", "description", "File path relative to a repo root, or absolute inside a root"),
                            "startLine", Map.of("type", "integer", "description", "1-based start line (optional)"),
                            "endLine", Map.of("type", "integer", "description", "1-based inclusive end line (optional)")
                    ),
                    "required", new String[]{"path"}
            )
    );

    private final CodeAccessGuard guard;
    private final long maxFileBytes;

    public ReadFileTool(CodeAccessGuard guard, long maxFileBytes) {
        super(DEFINITION);
        this.guard = guard;
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Path file = guard.resolve(str(parameters.get("path")));
            if (!Files.exists(file)) {
                return ToolResult.failure("file not found: " + file.getFileName());
            }
            if (!Files.isRegularFile(file)) {
                return ToolResult.failure("not a regular file: " + file.getFileName());
            }
            long size = Files.size(file);
            byte[] bytes = Files.readAllBytes(file);
            boolean truncated = bytes.length > maxFileBytes;
            String content = new String(bytes, 0,
                    (int) Math.min(bytes.length, maxFileBytes), StandardCharsets.UTF_8);

            Integer start = intParam(parameters.get("startLine"));
            Integer end = intParam(parameters.get("endLine"));
            if (start != null || end != null) {
                return ToolResult.success(slice(content, start, end, size, truncated));
            }
            return ToolResult.success(truncated
                    ? content + "\n... [truncated at " + maxFileBytes + " bytes of " + size + "]"
                    : content);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("read_file failed", e);
            return ToolResult.failure(e.getMessage());
        }
    }

    private String slice(String content, Integer start, Integer end, long size, boolean truncated) {
        List<String> lines = content.lines().toList();
        int from = start != null ? Math.max(1, start) : 1;
        int to = end != null ? Math.min(lines.size(), end) : lines.size();
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i).append('\t').append(lines.get(i - 1)).append('\n');
        }
        if (truncated) {
            sb.append("... [truncated at ").append(maxFileBytes).append(" bytes of ").append(size).append("]");
        }
        return sb.toString();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intParam(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
