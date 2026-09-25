package org.skylark.langur.infrastructure.harness.tool.rest;

/**
 * 凭证类型（§7.4）- CredentialVault 支持的托管方式。
 */
public enum CredentialType {
    /** Authorization: Bearer {secret} */
    BEARER,
    /** 自定义头 headerName: secret */
    API_KEY,
    /** Authorization: Basic base64(user:secret) */
    BASIC,
    /** OAuth2 client_credentials：向 tokenUrl 换取访问令牌，Authorization: Bearer {access_token}，到期自动刷新（H7） */
    OAUTH2
}
