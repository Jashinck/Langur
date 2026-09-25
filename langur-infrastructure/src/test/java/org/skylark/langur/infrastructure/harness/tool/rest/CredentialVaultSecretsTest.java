package org.skylark.langur.infrastructure.harness.tool.rest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7 验收：{@link InMemoryCredentialVault} 经 {@link SecretResolver} 解析 env/prop/kms 引用（明文不落配置/日志），
 * OAUTH2 凭证经 {@link OAuth2TokenManager} 换取 Bearer 令牌；KMS 缺失却用 kms: 引用时 fail-closed；密钥不泄露于 toString。
 */
class CredentialVaultSecretsTest {

    private OAuth2TokenManager tokenManagerReturning(String token) {
        OAuth2TokenClient client = (tokenUrl, clientId, clientSecret, scope) -> new AccessToken(token, 3600);
        return new OAuth2TokenManager(client, () -> 0L);
    }

    @Test
    void shouldResolveOAuth2BearerHeader() {
        InMemoryCredentialVault vault =
                new InMemoryCredentialVault(new CompositeSecretResolver((KmsClient) null), tokenManagerReturning("abc123"));
        vault.put(Credential.builder().ref("oa").type(CredentialType.OAUTH2)
                .tokenUrl("https://idp/token").clientId("cid").clientSecret("csec").scope("s").build());

        Map<String, String> headers = vault.resolveHeaders("oa");

        assertEquals("Bearer abc123", headers.get("Authorization"));
    }

    @Test
    void shouldResolveKmsSecretReference() {
        KmsClient kms = ciphertext -> "plain-" + ciphertext;
        InMemoryCredentialVault vault =
                new InMemoryCredentialVault(new CompositeSecretResolver(kms), tokenManagerReturning("x"));
        vault.put(Credential.builder().ref("k").type(CredentialType.BEARER).secret("kms:vault/secret/1").build());

        assertEquals("Bearer plain-vault/secret/1", vault.resolveHeaders("k").get("Authorization"));
    }

    @Test
    void shouldResolveSystemPropertySecretReference() {
        System.setProperty("langur.test.apikey", "propval");
        try {
            InMemoryCredentialVault vault =
                    new InMemoryCredentialVault(new CompositeSecretResolver((KmsClient) null), tokenManagerReturning("x"));
            vault.put(Credential.builder().ref("p").type(CredentialType.API_KEY)
                    .headerName("X-Api-Key").secret("prop:langur.test.apikey").build());

            assertEquals("propval", vault.resolveHeaders("p").get("X-Api-Key"));
        } finally {
            System.clearProperty("langur.test.apikey");
        }
    }

    @Test
    void shouldFailClosedWhenKmsMissingButReferenced() {
        InMemoryCredentialVault vault =
                new InMemoryCredentialVault(new CompositeSecretResolver((KmsClient) null), tokenManagerReturning("x"));
        vault.put(Credential.builder().ref("bad").type(CredentialType.BEARER).secret("kms:nope").build());

        assertThrows(IllegalStateException.class, () -> vault.resolveHeaders("bad"));
    }

    @Test
    void shouldThrowWhenOAuth2ButNoTokenManager() {
        InMemoryCredentialVault vault = new InMemoryCredentialVault();
        vault.put(Credential.builder().ref("oa").type(CredentialType.OAUTH2)
                .tokenUrl("https://idp/token").clientId("cid").clientSecret("csec").build());

        assertThrows(IllegalStateException.class, () -> vault.resolveHeaders("oa"));
    }

    @Test
    void shouldNotLeakSecretInToString() {
        Credential credential = Credential.builder().ref("k").type(CredentialType.BEARER)
                .secret("super-secret-value").build();
        assertFalse(credential.toString().contains("super-secret-value"));
    }
}
