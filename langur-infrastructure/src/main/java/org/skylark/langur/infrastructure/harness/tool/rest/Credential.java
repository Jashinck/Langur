package org.skylark.langur.infrastructure.harness.tool.rest;

import lombok.Builder;
import lombok.Getter;

/**
 * 托管凭证条目 - 由 CredentialVault 统一管理，禁止在工具规格中明文携带密钥。
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
}
