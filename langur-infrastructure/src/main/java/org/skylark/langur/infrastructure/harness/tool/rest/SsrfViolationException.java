package org.skylark.langur.infrastructure.harness.tool.rest;

/**
 * SSRF 违规异常 - 目标地址命中内网/保留段且不在可信白名单时抛出。
 */
public class SsrfViolationException extends RuntimeException {

    public SsrfViolationException(String message) {
        super(message);
    }
}
