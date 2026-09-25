package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.HybridQuery;
import org.skylark.langur.domain.harness.context.vector.SearchFilter;
import org.skylark.langur.domain.harness.context.vector.SearchQuery;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfGuard;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.6 验收 - {@link MilvusVectorStore} 离线确定性单测（JDK HttpServer 桩，不依赖真实 Milvus）。
 * <p>覆盖：upsert + Bearer 认证（password-ref 经 SecretResolver，明文不落请求体/日志）、建 collection
 * best-effort（partition-key namespace + BM25 function + HNSW/COSINE）、dense 检索 + namespace 强制过滤 +
 * COSINE distance 分数语义 + minScore、谓词翻译为布尔表达式（含引号转义防注入）、delete、批量 upsert、
 * dense+sparse 两通道客户端 RRF 融合、sparse 通道不可用异常上抛（domain 回退应用侧 rerank，P10）。</p>
 */
class MilvusVectorStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "langur_vectors";

    private final List<HttpServer> servers = new ArrayList<>();
    private final Stub stub = new Stub();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    // —— 装配辅助 ——

    private MilvusVectorStore newStore(String username, String passwordRef, SecretResolver resolver)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", stub);
        server.start();
        servers.add(server);

        VectorProperties props = new VectorProperties();
        props.setStore("milvus");
        props.getMilvus().setUri("http://127.0.0.1:" + server.getAddress().getPort());
        props.getMilvus().setCollection(COLLECTION);
        props.getMilvus().setUsername(username);
        props.getMilvus().setPasswordRef(passwordRef);
        return new MilvusVectorStore(props, MAPPER, new SsrfGuard(), resolver);
    }

    private MilvusVectorStore newStore() throws IOException {
        return newStore("", null, null);
    }

    private static VectorRecord record(String namespace, String id, String content, float... vector) {
        return VectorRecord.builder()
                .id(id).namespace(namespace).content(content).vector(vector)
                .metadata(Map.of("lang", "zh"))
                .build();
    }

    // —— 写入 / 删除 ——

    @Test
    void shouldUpsertWithResolvedBearerAuthAndBestEffortSchema() throws Exception {
        SecretResolver resolver = reference -> Optional.of("s3cret");
        MilvusVectorStore store = newStore("root", "env:MILVUS_PW", resolver);

        store.upsert(record("legal", "doc1", "合同条款", 0.1f, 0.2f));

        RecordedRequest create = stub.requests.stream()
                .filter(r -> r.path.endsWith("/collections/create")).findFirst().orElseThrow();
        JsonNode schema = MAPPER.readTree(create.body).path("schema");
        JsonNode namespaceField = schema.path("fields").get(1);
        assertEquals("namespace", namespaceField.path("fieldName").asText());
        assertTrue(namespaceField.path("isPartitionKey").asBoolean(), "DD18：namespace 为 partition-key");
        assertEquals("BM25", schema.path("functions").get(0).path("type").asText());
        JsonNode dimField = schema.path("fields").get(4);
        assertEquals("2", dimField.path("elementTypeParams").path("dim").asText(),
                "维度由首条记录向量驱动（dimension=0 未显式配置）");
        assertEquals("Bearer root:s3cret", create.authorization, "password-ref 解析后仅注入认证头");

        RecordedRequest upsert = stub.requests.stream()
                .filter(r -> r.path.endsWith("/entities/upsert")).findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(upsert.body);
        assertEquals(COLLECTION, body.path("collectionName").asText());
        assertEquals("legal", body.path("data").get(0).path("namespace").asText());
        assertEquals(2, body.path("data").get(0).path("embedding").size());
        assertFalse(upsert.body.contains("s3cret"), "密码明文绝不落请求体");
    }

    @Test
    void shouldFailClosedWhenPasswordRefUnresolvable() throws Exception {
        SecretResolver emptyResolver = reference -> Optional.empty();
        assertThrows(IllegalStateException.class,
                () -> newStore("root", "kms:cipher", emptyResolver),
                "password-ref 解析为空必须 fail-closed，绝不静默匿名");
        assertThrows(IllegalStateException.class,
                () -> newStore("root", "env:MILVUS_PW", null));
    }

    @Test
    void shouldUpsertAllInSingleBatchRequest() throws Exception {
        MilvusVectorStore store = newStore();
        store.upsertAll("legal", List.of(
                record("legal", "d1", "条款一", 1f, 0f),
                record("legal", "d2", "条款二", 0f, 1f)));

        List<RecordedRequest> upserts = stub.requests.stream()
                .filter(r -> r.path.endsWith("/entities/upsert")).toList();
        assertEquals(1, upserts.size(), "批量走单次原生 upsert（H14.1）");
        assertEquals(2, MAPPER.readTree(upserts.get(0).body).path("data").size());
    }

    @Test
    void shouldDeleteByNamespaceAndIdFilter() throws Exception {
        MilvusVectorStore store = newStore();
        store.delete("legal", "doc1");

        RecordedRequest delete = stub.requests.stream()
                .filter(r -> r.path.endsWith("/entities/delete")).findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(delete.body);
        assertEquals("namespace == \"legal\" && id == \"doc1\"", body.path("filter").asText());
    }

    // —— 检索 ——

    @Test
    void shouldSearchDenseWithNamespaceFilterAndCosineScore() throws Exception {
        stub.denseResponse = hits(
                hit("doc1", "legal", "合同条款", 0.91),
                hit("doc2", "legal", "其他条款", 0.2));
        MilvusVectorStore store = newStore();

        List<VectorRecord> results = store.search(SearchQuery
                .of("legal", new float[]{1f, 0f}, 5)
                .withMinScore(0.5));

        assertEquals(1, results.size(), "minScore=0.5：distance 0.2 被过滤");
        assertEquals("doc1", results.get(0).getId());
        assertEquals(0.91, results.get(0).getScore(), 1e-9, "COSINE distance 即裸 cosine，同内存语义");

        JsonNode body = MAPPER.readTree(stub.searchRequests().get(0).body);
        assertEquals("embedding", body.path("annsField").asText());
        assertEquals("namespace == \"legal\"", body.path("filter").asText(),
                "namespace 强制过滤（越权红线），不依赖调用方自觉");
        assertEquals(5, body.path("limit").asInt());
    }

    @Test
    void shouldTranslateMetadataPredicatesToBooleanExpr() {
        String expr = MilvusVectorStore.filterExpr("legal", SearchFilter.of(
                SearchFilter.Predicate.eq("lang", "zh"),
                SearchFilter.Predicate.in("tag", List.of("a", "b")),
                SearchFilter.Predicate.gte("year", 2020),
                SearchFilter.Predicate.exists("clause")));

        assertEquals("namespace == \"legal\" && metadata[\"lang\"] == \"zh\""
                + " && metadata[\"tag\"] in [\"a\", \"b\"]"
                + " && metadata[\"year\"] >= 2020"
                + " && metadata[\"clause\"] exists", expr);
    }

    @Test
    void shouldEscapeQuotesInFilterValuesAgainstExprInjection() {
        String expr = MilvusVectorStore.filterExpr("le\"gal", SearchFilter.of(
                SearchFilter.Predicate.eq("k", "v\" || namespace != \"x")));

        assertEquals("namespace == \"le\\\"gal\""
                + " && metadata[\"k\"] == \"v\\\" || namespace != \\\"x\"", expr,
                "引号转义防布尔表达式注入");
    }

    // —— 混合检索 ——

    @Test
    void shouldSupportHybridCapability() throws Exception {
        assertTrue(newStore().supportsHybrid());
    }

    @Test
    void shouldHybridFuseDenseAndSparseClientSide() throws Exception {
        stub.denseResponse = hits(
                hit("B", "legal", "语义第一", 0.9),
                hit("C", "legal", "语义第二", 0.5));
        stub.sparseResponse = hits(
                hit("A", "legal", "词面第一", 3.1),
                hit("B", "legal", "词面第二", 2.0));
        MilvusVectorStore store = newStore();

        List<VectorRecord> fused = store.hybridSearch(HybridQuery.of("legal", "合同", new float[]{1f, 0f}, 3));

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList(),
                "RRF：B=1/61+1/62 > A=1/61 > C=1/62");
        assertEquals(1.0 / 61 + 1.0 / 62, fused.get(0).getScore(), 1e-9);

        List<RecordedRequest> searches = stub.searchRequests();
        assertEquals(2, searches.size(), "dense + sparse 两通道");
        JsonNode dense = MAPPER.readTree(searches.get(0).body);
        JsonNode sparse = MAPPER.readTree(searches.get(1).body);
        assertEquals("embedding", dense.path("annsField").asText());
        assertEquals("sparse", sparse.path("annsField").asText());
        assertEquals("合同", sparse.path("data").get(0).asText(),
                "Milvus 2.5 BM25 function：sparse 通道直接传查询文本");
    }

    @Test
    void shouldPropagateFailureWhenSparseChannelUnavailable() throws Exception {
        stub.denseResponse = hits(hit("B", "legal", "语义", 0.9));
        stub.sparseCode = 1800; // 无 BM25 function / 旧版本
        stub.sparseMessage = "sparse field not found";
        MilvusVectorStore store = newStore();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.hybridSearch(HybridQuery.of("legal", "合同", new float[]{1f, 0f}, 3)));
        assertTrue(e.getMessage().contains("code=1800"), "fail-fast 不静默；domain 捕获后回退应用侧 rerank（P10）");
        assertFalse(e.getMessage().contains("s3cret"));
    }

    // —— 桩基础设施 ——

    private static String hits(String... hitJsons) {
        return "{\"code\":0,\"data\":[" + String.join(",", hitJsons) + "]}";
    }

    private static String hit(String id, String namespace, String content, double distance) {
        return "{\"id\":\"" + id + "\",\"namespace\":\"" + namespace
                + "\",\"content\":\"" + content + "\",\"metadata\":{\"lang\":\"zh\"}"
                + ",\"distance\":" + distance + "}";
    }

    private static final class RecordedRequest {
        final String path;
        final String body;
        final String authorization;

        RecordedRequest(String path, String body, String authorization) {
            this.path = path;
            this.body = body;
            this.authorization = authorization;
        }
    }

    private final class Stub implements com.sun.net.httpserver.HttpHandler {
        final List<RecordedRequest> requests = new ArrayList<>();
        volatile String denseResponse = hits();
        volatile String sparseResponse = hits();
        volatile int sparseCode = 0;
        volatile String sparseMessage = "";

        List<RecordedRequest> searchRequests() {
            return requests.stream().filter(r -> r.path.endsWith("/entities/search")).toList();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(path, body,
                    exchange.getRequestHeaders().getFirst("Authorization")));

            String response;
            if (path.endsWith("/entities/search") && body.contains("\"annsField\":\"sparse\"")) {
                response = sparseCode != 0
                        ? "{\"code\":" + sparseCode + ",\"message\":\"" + sparseMessage + "\"}"
                        : sparseResponse;
            } else if (path.endsWith("/entities/search")) {
                response = denseResponse;
            } else {
                response = "{\"code\":0,\"data\":{}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
