package org.skylark.langur.domain.port;

import org.skylark.langur.domain.model.tool.Tool;

import java.util.List;

/**
 * 工具提供者端口接口 - 由基础设施层实现，用于注入工具列表
 */
public interface ToolProvider {
    List<Tool> getTools();
}
