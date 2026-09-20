package org.skylark.langur.common.spi;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * SPI 工具规格 - 业务域通过 {@link ToolProviderSPI} 暴露的工具描述（九元组子集）。
 * <p>与 domain 的 ToolDefinitionEntity 解耦：common 层仅用 JDK 类型，转换由 infra 承接。</p>
 */
@Getter
@Builder
public class SpiToolSpec {

    private final String toolId;
    private final String description;
    /** 入参 JSON Schema（type/properties/required）。 */
    private final Map<String, Object> inputSchema;
    /** 权限标识，供权限校验层使用。 */
    private final String permission;
    /** 风险等级字符串（LOW/MEDIUM/HIGH/CRITICAL）。 */
    private final String riskLevel;
}
