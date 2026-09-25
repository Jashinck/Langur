package org.skylark.langur.infrastructure.harness.tool.rest;

import lombok.Builder;
import lombok.Getter;

/**
 * 托管凭证条目 - 由 CredentialVault 统一管理，禁止在工具规格中明文携带密钥。
 * <p>H7：新增 OAUTH2（client_credentials）字段；所有密钥字段（{@code secret}/{@code clientSecret}）
 * 支持 {@code env:}/{@code prop:}/{@code kms:} 引用，由 {@link SecretResolver} 按需解密，明文不落配置与日志。
 * 不生成 {@code toString}，避免密钥经日志泄露。</p>
 */
@Getter
@Builder
public class Credential {

    private final String ref;
    private final CredentialType type;
    /** API_KEY 类型的目标头名（如 X-Api-Key）。 */
    private final String headerName;
    /** BASIC 类型的用户名。 */
    private final String username;
    private final String secret;

    // ---- OAUTH2 (client_credentials) ----
    /** 令牌端点。 */
    private final String tokenUrl;
    private final String clientId;
    /** 客户端密钥（可为 {@code env:}/{@code kms:} 引用）。 */
    private final String clientSecret;
    /** 可选 scope（空格分隔）。 */
    private final String scope;

    /** OAUTH2 令牌缓存键：同一 ref 复用缓存，跨 ref 隔离。 */
    public String tokenCacheKey() {
        return ref != null ? ref : (clientId + "@" + tokenUrl);
    }
}
