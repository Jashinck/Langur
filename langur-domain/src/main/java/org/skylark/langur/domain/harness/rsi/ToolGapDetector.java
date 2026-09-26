package org.skylark.langur.domain.harness.rsi;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 工具能力缺口检测器（R5）——从任务文本判定既有工具集是否覆盖所需能力，缺口给出候选关键词。
 * <p>确定性模板实现（离线零网络）：任务文本命中的领域关键词若不在既有能力描述中，即判为缺口并返回该关键词，
 * 供候选检索（MCP 注册表 / OpenAPI 目录）锚定。v3.0 增量（决策平面 {@code noul("当前工具集是否足以完成任务")}
 * 做廉价归因初筛）为后续增强，本模板是缺省确定性基线（P10）。纯 JDK 零外部依赖（P1）。</p>
 */
public class ToolGapDetector {

    /**
     * 检测能力缺口。
     *
     * @param task                 任务描述（用户/编排输入）
     * @param availableCapabilities 既有工具的能力描述集（如工具名/描述关键词）
     * @param capabilityKeywords   候选能力关键词表（按需配置）
     * @return 命中的缺口关键词；无缺口返回 {@link Optional#empty()}
     */
    public Optional<String> detectGap(String task, List<String> availableCapabilities,
                                      List<String> capabilityKeywords) {
        if (task == null || task.isBlank() || capabilityKeywords == null || capabilityKeywords.isEmpty()) {
            return Optional.empty();
        }
        String lowerTask = task.toLowerCase(Locale.ROOT);
        for (String keyword : capabilityKeywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
            if (!lowerTask.contains(lowerKeyword)) {
                continue;
            }
            boolean covered = availableCapabilities != null && availableCapabilities.stream()
                    .anyMatch(c -> c != null && c.toLowerCase(Locale.ROOT).contains(lowerKeyword));
            if (!covered) {
                return Optional.of(keyword);
            }
        }
        return Optional.empty();
    }
}
