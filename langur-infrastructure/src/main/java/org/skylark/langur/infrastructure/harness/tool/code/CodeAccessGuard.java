package org.skylark.langur.infrastructure.harness.tool.code;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 代码访问安全护栏（Coding Agent）- 把用户/模型提供的路径限制在配置的仓库根白名单内。
 * <p>防护点：路径归一化后必须 {@code startsWith} 某个 root（杜绝 {@code ../} 遍历与绝对路径越权）；
 * 命中 {@code excludeDirs}（如 .git/target/node_modules）的路径一律拒绝或遍历时跳过。
 * 纯 JDK 实现、无 Spring 依赖，可离线单测。</p>
 */
public class CodeAccessGuard {

    private final List<Path> roots;
    private final Set<String> excludeDirs;
    private final boolean enabled;

    public CodeAccessGuard(CodeAccessProperties properties) {
        this.enabled = properties != null && properties.isEnabled();
        this.roots = new ArrayList<>();
        this.excludeDirs = new HashSet<>();
        if (properties != null) {
            for (String root : properties.getRoots()) {
                if (root != null && !root.isBlank()) {
                    this.roots.add(Paths.get(root).toAbsolutePath().normalize());
                }
            }
            if (properties.getExcludeDirs() != null) {
                this.excludeDirs.addAll(properties.getExcludeDirs());
            }
        }
    }

    /** 工具是否可用：启用且至少配置一个 root。 */
    public boolean available() {
        return enabled && !roots.isEmpty();
    }

    public List<Path> roots() {
        return List.copyOf(roots);
    }

    /**
     * 解析并校验路径，返回落在白名单内的绝对归一化路径。
     *
     * @throws IllegalArgumentException 路径为空、越界或命中排除目录
     */
    public Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path candidate = confine(Paths.get(rawPath));
        if (isExcluded(candidate)) {
            throw new IllegalArgumentException("path is inside an excluded directory: " + rawPath);
        }
        return candidate;
    }

    /** 把（可能相对的）路径限定到某个 root 内；越界抛异常。 */
    private Path confine(Path path) {
        for (Path root : roots) {
            Path candidate = path.isAbsolute()
                    ? path.normalize()
                    : root.resolve(path).normalize();
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("path escapes allowed roots: " + path);
    }

    /** 路径任一段命中排除目录名即为 true（遍历时用于跳过）。 */
    public boolean isExcluded(Path path) {
        if (path == null) {
            return false;
        }
        for (Path segment : path) {
            if (excludeDirs.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
