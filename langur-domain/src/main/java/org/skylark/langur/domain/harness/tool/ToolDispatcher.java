package org.skylark.langur.domain.harness.tool;

/**
 * T 组件 - 工具统一路由调度器（端口）。
 * <p>按 toolSource 路由到 LOCAL/SPI/MCP/REST_API/SKILL 执行器，
 * 所有来源经同一套四层校验链管控，LLM 无感知差异；具体实现落于基础设施层。</p>
 */
public interface ToolDispatcher {

    ToolCallResult dispatch(ToolCallRequest request);
}
