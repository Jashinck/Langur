package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * t_tool_definition - 工具定义（九元组 + 限流配置）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_tool_definition")
public class ToolDefinitionDO {

    @Id
    private String toolId;

    @Lob
    private String description;

    @Lob
    private String inputSchema;

    @Lob
    private String outputSchema;

    private String permission;

    private String riskLevel;

    @Lob
    private String whitelist;

    private Integer rateLimitPerMinute;

    private Long timeoutMillis;

    private String source;
}
