package org.skylark.langur.infrastructure.harness.tool.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7 验收：{@link OpenApiSpecLoader} 自动识别内联 JSON/YAML 文档并解析为 Map；缺来源或空文档 fail-fast。
 */
class OpenApiSpecLoaderTest {

    private final OpenApiSpecLoader loader = new OpenApiSpecLoader(new ObjectMapper(), new SsrfGuard());

    private RestApiToolProperties.OpenApiSource source(String spec) {
        RestApiToolProperties.OpenApiSource src = new RestApiToolProperties.OpenApiSource();
        src.setServiceName("svc");
        src.setSpec(spec);
        return src;
    }

    @Test
    void shouldParseInlineJsonDocument() {
        Map<String, Object> doc = loader.load(source("{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{}}}}"));
        assertEquals("3.0.0", doc.get("openapi"));
        assertTrue(doc.get("paths") instanceof Map<?, ?>);
    }

    @Test
    void shouldParseInlineYamlDocument() {
        String yaml = """
                openapi: 3.0.0
                servers:
                  - url: https://api.example.com
                paths:
                  /a:
                    get:
                      operationId: getA
                """;
        Map<String, Object> doc = loader.load(source(yaml));
        assertEquals("3.0.0", String.valueOf(doc.get("openapi")));
        assertTrue(doc.get("paths") instanceof Map<?, ?>);
    }

    @Test
    void shouldThrowWhenNoSourceProvided() {
        RestApiToolProperties.OpenApiSource src = new RestApiToolProperties.OpenApiSource();
        src.setServiceName("svc");
        assertThrows(IllegalStateException.class, () -> loader.load(src));
    }

    @Test
    void shouldThrowWhenDocumentBlank() {
        assertThrows(IllegalStateException.class, () -> loader.load(source("   ")));
    }

    @Test
    void shouldExposeDiscoveredToolsEndToEnd() {
        Map<String, Object> doc = loader.load(source(
                "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                        + "\"paths\":{\"/a\":{\"get\":{\"operationId\":\"getA\"}}}}"));
        RestApiToolProperties.OpenApiSource src = source(null);
        List<RestApiToolSpec> specs = new OpenApiToolImporter().toSpecs(doc, src);
        assertEquals(1, specs.size());
        assertEquals("https://api.example.com/a", specs.get(0).getUrlTemplate());
    }
}
