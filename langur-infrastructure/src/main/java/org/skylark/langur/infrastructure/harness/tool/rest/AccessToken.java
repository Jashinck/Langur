package org.skylark.langur.infrastructure.harness.tool.rest;

/**
 * OAuth2 访问令牌（H7）- 携带有效期以支撑到期刷新。
 *
 * @param token           访问令牌（access_token）
 * @param expiresInSeconds 有效期秒数；<=0 表示服务端未声明有效期（按不缓存处理）
 */
public record AccessToken(String token, long expiresInSeconds) {
}
