package org.skylark.langur.infrastructure.harness.tool.rest;

import java.util.Map;

/**
 * REST API 工具网关（§7.2 REST_API 路由点）- 由 T 组件调度器在四层校验通过后调用。
 * <p>实现负责 URL 模板解析、凭证注入、SSRF 防护与 HTTP 执行。</p>
 */
public interface RestApiToolGateway {

    /** 该网关是否支持指定工具（存在对应规格）。 */
    boolean supports(String toolId);

    /** 执行 REST 调用，返回响应体字符串。 */
    String execute(String toolId, Map<String, Object> arguments) throws Exception;
}
