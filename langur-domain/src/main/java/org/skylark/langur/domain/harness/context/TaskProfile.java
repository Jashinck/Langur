package org.skylark.langur.domain.harness.context;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 任务画像 - 风险 / 工具链 / Prompt 模板
 */
@Getter
@Builder
public class TaskProfile {

    private final String bizCode;
    private final String riskLevel;
    private final List<String> allowedToolIds;
    private final String promptTemplate;
}
