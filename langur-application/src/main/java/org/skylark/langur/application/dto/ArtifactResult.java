package org.skylark.langur.application.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 执行产物结果（多产物输出）- 承载一份具名交付物，如合同审查报告 / 特批项报告。
 */
@Data
@Builder
public class ArtifactResult {
    private String name;
    private String type;
    private String content;
}
