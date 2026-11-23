package org.skylark.langur.infrastructure.harness.tool.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolSource;
import org.skylark.langur.infrastructure.harness.tool.InMemoryToolRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7 验收：{@link RestApiToolRegistrar} 端到端自动发现——给定内联 OpenAPI 文档，启动注册即把发现的操作
 * 落入目录与 T 组件统一注册中心（source=REST_API，经四层校验链调用）；凭证装载（含 OAUTH2）；
 * 单个来源解析失败仅降级跳过，不阻断注册（P10）；总开关关闭时无副作用。
 */
class RestApiToolRegistrarOpenApiTest {

    private static final String DOC = """
            {"openapi":"3.0.0","servers":[{"url":"https://api.example.com/v1"}],
             "paths":{"/pets":{"get":{"operationId":"listPets","summary":"List pets"}},
                     "/pets/{petId}":{"get":{"operationId":"getPet"}}}}
            """;

    private RestApiToolRegistrar registrar(RestApiToolProperties props, RestApiToolCatalog catalog,
                                           InMemoryToolRegistry registry) {
        return new RestApiToolRegistrar(props, catalog, registry, new InMemoryCredentialVault(),
                new OpenApiSpecLoader(new ObjectMapper(), new SsrfGuard()), new OpenApiToolImporter());
    }

    private RestApiToolProperties.OpenApiSource source(String spec) {
        RestApiToolProperties.OpenApiSource src = new RestApiToolProperties.OpenApiSource();
        src.setServiceName("petstore");
        src.setSpec(spec);
        src.setCredentialRef("petstore-oauth");
        src.setRiskLevel("MEDIUM");
        return src;
    }

    @Test
    void shouldAutoDiscoverAndRegisterToolsFromOpenApi() {
        RestApiToolProperties props = new RestApiToolProperties();
        props.setEnabled(true);
        props.getOpenapi().add(source(DOC));
        RestApiToolCatalog catalog = new RestApiToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        registrar(props, catalog, registry).register();

        assertTrue(catalog.find("api:petstore:listPets").isPresent());
        assertTrue(catalog.find("api:petstore:getPet").isPresent());
        assertTrue(registry.find("api:petstore:listPets").isPresent());
        assertEquals(ToolSource.REST_API, registry.find("api:petstore:listPets").get().getSource());
        assertEquals("https://api.example.com/v1/pets",
                catalog.find("api:petstore:listPets").get().getUrlTemplate());
    }

    @Test
    void shouldLoadOAuth2Credential() {
        RestApiToolProperties props = new RestApiToolProperties();
        props.setEnabled(true);
        RestApiToolProperties.CredentialProps cred = new RestApiToolProperties.CredentialProps();
        cred.setRef("petstore-oauth");
        cred.setType("OAUTH2");
        cred.setTokenUrl("https://idp/token");
        cred.setClientId("cid");
        cred.setClientSecret("kms:petstore/secret");
        cred.setScope("pets");
        props.getCredentials().add(cred);
        props.getOpenapi().add(source(DOC));
        RestApiToolCatalog catalog = new RestApiToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        InMemoryCredentialVault vault = new InMemoryCredentialVault();

        new RestApiToolRegistrar(props, catalog, registry, vault,
                new OpenApiSpecLoader(new ObjectMapper(), new SsrfGuard()), new OpenApiToolImporter()).register();

        assertTrue(vault.find("petstore-oauth").isPresent());
        assertEquals(CredentialType.OAUTH2, vault.find("petstore-oauth").get().getType());
        assertEquals("https://idp/token", vault.find("petstore-oauth").get().getTokenUrl());
    }

    @Test
    void shouldDegradeWhenOpenApiSourceUnparseable() {
        RestApiToolProperties props = new RestApiToolProperties();
        props.setEnabled(true);
        props.getOpenapi().add(source(null)); // 无任何来源 → 加载失败
        props.getOpenapi().add(source(DOC));  // 正常来源仍应注册
        RestApiToolCatalog catalog = new RestApiToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        assertDoesNotThrow(() -> registrar(props, catalog, registry).register());

        assertTrue(catalog.find("api:petstore:listPets").isPresent());
    }

    @Test
    void shouldStayIdleWhenDisabled() {
        RestApiToolProperties props = new RestApiToolProperties();
        props.setEnabled(false);
        props.getOpenapi().add(source(DOC));
        RestApiToolCatalog catalog = new RestApiToolCatalog();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        registrar(props, catalog, registry).register();

        assertTrue(catalog.all().isEmpty());
    }
}
