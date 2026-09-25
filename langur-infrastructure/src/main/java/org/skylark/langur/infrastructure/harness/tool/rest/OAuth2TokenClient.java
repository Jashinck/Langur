package org.skylark.langur.infrastructure.harness.tool.rest;

/**
 * OAuth2 令牌获取接缝（H7）- 以 client_credentials 授权向令牌端点换取访问令牌。
 * <p>抽象成接口以便生产走真实 HTTP、单测注入桩，规避网络。</p>
 */
@FunctionalInterface
public interface OAuth2TokenClient {

    /**
     * 执行 client_credentials 授权。
     *
     * @param tokenUrl     令牌端点
     * @param clientId     客户端 ID
     * @param clientSecret 客户端密钥（已由 {@link SecretResolver} 解析为明文）
     * @param scope        可选 scope（空格分隔，可为 null）
     * @return 访问令牌及有效期
     */
    AccessToken fetchToken(String tokenUrl, String clientId, String clientSecret, String scope);
}
