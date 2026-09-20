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
    BASIC
}
