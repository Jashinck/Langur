package org.skylark.langur.infrastructure.harness.tool.rest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10a 验收：CredentialVault 三种凭证类型正确转换为请求头，密钥不出库。
 */
class CredentialVaultTest {

    private final InMemoryCredentialVault vault = new InMemoryCredentialVault();

    @Test
    void shouldResolveBearerHeader() {
        vault.put(Credential.builder().ref("r1").type(CredentialType.BEARER).secret("tok").build());
        Map<String, String> headers = vault.resolveHeaders("r1");
        assertEquals("Bearer tok", headers.get("Authorization"));
    }

    @Test
    void shouldResolveApiKeyHeader() {
        vault.put(Credential.builder().ref("r2").type(CredentialType.API_KEY)
                .headerName("X-Api-Key").secret("k").build());
        assertEquals("k", vault.resolveHeaders("r2").get("X-Api-Key"));
    }

    @Test
    void shouldResolveBasicHeader() {
        vault.put(Credential.builder().ref("r3").type(CredentialType.BASIC)
                .username("u").secret("p").build());
        String auth = vault.resolveHeaders("r3").get("Authorization");
        assertTrue(auth.startsWith("Basic "));
        // base64("u:p") = dTpw
        assertEquals("Basic dTpw", auth);
    }

    @Test
    void shouldReturnEmptyForUnknownRef() {
        assertTrue(vault.resolveHeaders("missing").isEmpty());
        assertTrue(vault.resolveHeaders(null).isEmpty());
    }
}
