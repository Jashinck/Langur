package org.skylark.langur.infrastructure.harness.tool.code;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码访问工具源配置（Coding Agent，P9）- {@code langur.code-access}。
 * <p>为长任务 Coding Agent 提供受控的仓库读取能力（read_file/list_dir/grep）。默认关闭（P10），
 * 启用后必须配置 {@code roots} 白名单：所有路径经归一化后须落在某个 root 内，杜绝越权/路径遍历。
 * 大仓防护：单文件读取上限 {@code maxFileBytes}、grep 结果上限 {@code maxResults}、
 * {@code excludeGlobs} 跳过 .git/target/node_modules 等目录。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.code-access")
public class CodeAccessProperties {

    /** 总开关，默认关闭（未配置 roots 时不产生副作用）。 */
    private boolean enabled = false;

    /** 允许访问的仓库根目录白名单（绝对路径）；为空则工具降级不可用。 */
    private List<String> roots = new ArrayList<>();

    /** 单文件读取字节上限，超出截断（防大文件撑爆上下文）。 */
    private long maxFileBytes = 262_144L;

    /** grep 命中行上限（防大仓遍历失控）。 */
    private int maxResults = 200;

    /** grep/list 遍历时跳过的目录名（精确匹配路径任一段）。 */
    private List<String> excludeDirs = new ArrayList<>(List.of(".git", "target", "node_modules", "build", "dist"));
}
