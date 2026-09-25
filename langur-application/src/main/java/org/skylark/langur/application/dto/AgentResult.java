package org.skylark.langur.application.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentResult {
    private String id;
    private String name;
    private String description;
    private String status;
    private String model;
    private int iterationCount;
    private int toolCount;
    private String lastError;
    private String answer;
    /** 多产物输出：具名交付物列表（如合同审查报告 + 特批项报告）；无产物时为空。 */
    private List<ArtifactResult> artifacts;
    private String createdAt;
    private String updatedAt;
}
