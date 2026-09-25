package org.skylark.langur.infrastructure.harness.tool.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7 验收：{@link OpenApiToolImporter} 将 OpenAPI 文档解析为工具规格——servers 基址拼接、
 * operationId 提取、path/query 参数与 requestBody（含 {@code $ref}）合成 inputSchema、
 * summary 作描述、includeOperations 白名单生效、来源默认（凭证/权限/风险/超时）透传。
 */
class OpenApiToolImporterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenApiToolImporter importer = new OpenApiToolImporter();

    private static final String DOC = """
            {
              "openapi": "3.0.0",
              "servers": [{"url": "https://api.example.com/v1/"}],
              "paths": {
                "/pets": {
                  "get": {"operationId":"listPets","summary":"List pets",
                          "parameters":[{"name":"limit","in":"query","required":false,"schema":{"type":"integer"}}]},
                  "post": {"operationId":"createPet","summary":"Create a pet",
                           "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}}
                },
                "/pets/{petId}": {
                  "get": {"operationId":"getPet",
                          "parameters":[{"name":"petId","in":"path","required":true,"schema":{"type":"string"}}]}
                }
              },
              "components": {"schemas": {"Pet": {"type":"object",
                "properties":{"name":{"type":"string"},"tag":{"type":"string"}},"required":["name"]}}}
            }
            """;

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc() throws Exception {
        return mapper.readValue(DOC, Map.class);
    }

    private RestApiToolProperties.OpenApiSource source(String serviceName) {
        RestApiToolProperties.OpenApiSource src = new RestApiToolProperties.OpenApiSource();
        src.setServiceName(serviceName);
        src.setCredentialRef("petstore-oauth");
        src.setPermission("pet:write");
        src.setRiskLevel("MEDIUM");
        src.setTimeoutSeconds(15);
        return src;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputSchema(RestApiToolSpec spec) {
        return spec.getInputSchema();
    }

    @Test
    void shouldDiscoverAllOperationsWithBaseUrlFromServers() throws Exception {
        List<RestApiToolSpec> specs = importer.toSpecs(doc(), source("petstore"));

        assertEquals(3, specs.size());
        RestApiToolSpec list = specs.stream().filter(s -> s.getOperationId().equals("listPets")).findFirst().orElseThrow();
        assertEquals("GET", list.getMethod());
        assertEquals("https://api.example.com/v1/pets", list.getUrlTemplate());
        assertEquals("api:petstore:listPets", list.toolId());
        assertEquals("List pets", list.getDescription());
        assertEquals("petstore-oauth", list.getCredentialRef());
        assertEquals("MEDIUM", list.getRiskLevel());
        assertEquals("pet:write", list.getPermission());
    }

    @Test
    void shouldBuildInputSchemaFromQueryAndPathParams() throws Exception {
        List<RestApiToolSpec> specs = importer.toSpecs(doc(), source("petstore"));

        RestApiToolSpec list = specs.stream().filter(s -> s.getOperationId().equals("listPets")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) inputSchema(list).get("properties");
        assertEquals("integer", ((Map<?, ?>) props.get("limit")).get("type"));
        assertFalse(inputSchema(list).containsKey("required"), "optional param should not be required");

        RestApiToolSpec get = specs.stream().filter(s -> s.getOperationId().equals("getPet")).findFirst().orElseThrow();
        assertEquals("https://api.example.com/v1/pets/{petId}", get.getUrlTemplate());
        @SuppressWarnings("unchecked")
        Map<String, Object> getProps = (Map<String, Object>) inputSchema(get).get("properties");
        assertEquals("string", ((Map<?, ?>) getProps.get("petId")).get("type"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) inputSchema(get).get("required");
        assertTrue(required.contains("petId"));
    }

    @Test
    void shouldResolveRequestBodyRefIntoProperties() throws Exception {
        List<RestApiToolSpec> specs = importer.toSpecs(doc(), source("petstore"));

        RestApiToolSpec create = specs.stream().filter(s -> s.getOperationId().equals("createPet")).findFirst().orElseThrow();
        assertEquals("POST", create.getMethod());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) inputSchema(create).get("properties");
        assertTrue(props.containsKey("name"), "$ref Pet.name should be inlined");
        assertTrue(props.containsKey("tag"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) inputSchema(create).get("required");
        assertTrue(required.contains("name"));
    }

    @Test
    void shouldHonorIncludeOperationsWhitelist() throws Exception {
        RestApiToolProperties.OpenApiSource src = source("petstore");
        src.setIncludeOperations(List.of("getPet"));

        List<RestApiToolSpec> specs = importer.toSpecs(doc(), src);

        assertEquals(1, specs.size());
        assertEquals("getPet", specs.get(0).getOperationId());
    }

    @Test
    void shouldOverrideBaseUrlWhenConfigured() throws Exception {
        RestApiToolProperties.OpenApiSource src = source("petstore");
        src.setBaseUrl("https://gw.internal/api");

        List<RestApiToolSpec> specs = importer.toSpecs(doc(), src);

        RestApiToolSpec list = specs.stream().filter(s -> s.getOperationId().equals("listPets")).findFirst().orElseThrow();
        assertEquals("https://gw.internal/api/pets", list.getUrlTemplate());
    }

    @Test
    void shouldReturnEmptyWhenNoPaths() {
        RestApiToolProperties.OpenApiSource src = source("empty");
        assertTrue(importer.toSpecs(Map.of("openapi", "3.0.0"), src).isEmpty());
        assertTrue(importer.toSpecs(null, src).isEmpty());
    }
}
