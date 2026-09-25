package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.FusionMode;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.5 验收 - {@link ElasticsearchVectorStore} 离线确定性单测（JDK HttpServer 桩，不依赖真实 ES）。
 * <p>覆盖：namespace::id UPSERT + Basic 认证（password-ref 经 SecretResolver，明文不落请求体）、
 * knn 检索 + namespace 强制过滤 + cosine 分数还原 + minScore/metadata 谓词翻译、
 * DD20 缺省客户端 RRF 融合、native-rrf 授权开启与失败自动降级、delete 幂等、_bulk 批量、融合纯函数。</p>
 */
class ElasticsearchVectorStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "langur_vectors";

    private final List<HttpServer> servers = new ArrayList<>();
    private final Stub stub = new Stub();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    // —— 装配辅助 ——

    private ElasticsearchVectorStore newStore(boolean nativeRrf, String username, String passwordRef,
                                              SecretResolver resolver) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", stub);
        server.start();
        servers.add(server);

        VectorProperties props = new VectorProperties();
        props.setStore("elasticsearch");
        props.getElasticsearch().setUris("http://127.0.0.1:" + server.getAddress().getPort());
        props.getElasticsearch().setIndex(INDEX);
        props.getElasticsearch().setUsername(username);
        props.getElasticsearch().setPasswordRef(passwordRef);
        props.getElasticsearch().setNativeRrf(nativeRrf);
        return new ElasticsearchVectorStore(props, MAPPER, new SsrfGuard(), resolver);
    }

    private ElasticsearchVectorStore newStore() throws IOException {
        return newStore(false, "", null, null);
    }

    private static VectorRecord record(String namespace, String id, String content, float... vector) {
        return VectorRecord.builder()
                .id(id).namespace(namespace).content(content).vector(vector)
                .metadata(Map.of("lang", "zh"))
                .build();
    }

    // —— 写入 / 删除 ——

    @Test
    void shouldUpsertWithNamespaceScopedDocIdAndResolvedBasicAuth() throws Exception {
        SecretResolver resolver = reference -> Optional.of("s3cret");
        ElasticsearchVectorStore store = newStore(false, "elastic", "env:ES_PW", resolver);

        store.upsert(record("legal", "doc1", "合同条款", 0.1f, 0.2f, 0.3f, 0.4f));

        // 首写触发 best-effort 建索引
        RecordedRequest ensure = stub.requests.stream()
                .filter(r -> r.method.equals("PUT") && r.path.equals("/" + INDEX)).findFirst().orElseThrow();
        JsonNode mapping = MAPPER.readTree(ensure.body)
                .path("mappings").path("properties").path("embedding");
        assertEquals("dense_vector", mapping.path("type").asText());
        assertEquals(4, mapping.path("dims").asInt(), "维度由首条记录向量驱动（dimension=0 未显式配置）");
        assertEquals("cosine", mapping.path("similarity").asText());

        RecordedRequest upsert = stub.requests.stream()
                .filter(r -> r.method.equals("PUT") && r.path.contains("_doc")).findFirst().orElseThrow();
        assertEquals("/" + INDEX + "/_doc/legal%3A%3Adoc1", upsert.path, "DD18：_id = namespace::id（URL 编码）");
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:s3cret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedAuth, upsert.authorization, "password-ref 解析后仅注入认证头");
        JsonNode body = MAPPER.readTree(upsert.body);
        assertEquals("legal", body.path("namespace").asText());
        assertEquals(4, body.path("embedding").size());
        assertFalse(upsert.body.contains("s3cret"), "密码明文绝不落请求体");
    }

    @Test
    void shouldFailClosedWhenPasswordRefUnresolvable() throws Exception {
        SecretResolver emptyResolver = reference -> Optional.empty();
        assertThrows(IllegalStateException.class,
                () -> newStore(false, "elastic", "kms:cipher", emptyResolver),
                "password-ref 解析为空必须 fail-closed，绝不静默匿名");
    }

    @Test
    void shouldFailClosedWhenNoSecretResolverForPasswordRef() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> newStore(false, "elastic", "env:ES_PW", null));
    }

    @Test
    void shouldDeleteDocAndTolerateNotFound() throws Exception {
        ElasticsearchVectorStore store = newStore();
        store.delete("legal", "doc1");
        RecordedRequest delete = stub.requests.stream()
                .filter(r -> r.method.equals("DELETE")).findFirst().orElseThrow();
        assertEquals("/" + INDEX + "/_doc/legal%3A%3Adoc1", delete.path);

        stub.deleteStatus = 404;
        store.delete("legal", "missing");
        // 幂等：404 不抛出
    }

    @Test
    void shouldUpsertAllViaBulkApi() throws Exception {
        ElasticsearchVectorStore store = newStore();
        store.upsertAll("legal", List.of(
                record("legal", "d1", "条款一", 1f, 0f),
                record("legal", "d2", "条款二", 0f, 1f)));

        RecordedRequest bulk = stub.requests.stream()
                .filter(r -> r.path.equals("/_bulk")).findFirst().orElseThrow();
        assertEquals("application/x-ndjson", bulk.contentType);
        String[] lines = bulk.body.split("\n");
        assertEquals(4, lines.length, "两条记录 = 2 组 action/source 行");
        assertTrue(lines[0].contains("\"_id\":\"legal%3A%3Ad1\""));
        assertTrue(lines[2].contains("\"_id\":\"legal%3A%3Ad2\""));
    }

    // —— 检索 ——

    @Test
    void shouldSearchWithKnnNamespaceFilterAndCosineTransform() throws Exception {
        stub.knnResponse = hits(
                hit("doc1", "legal", "合同条款", 1.0),
                hit("doc2", "legal", "其他条款", 0.75),
                hit("doc3", "legal", "无关内容", 0.5));
        ElasticsearchVectorStore store = newStore();

        List<VectorRecord> results = store.search("legal", new float[]{1f, 0f, 0f, 0f}, 3);

        assertEquals(3, results.size());
        assertEquals(1.0, results.get(0).getScore(), 1e-9, "ES cosine _score=(1+cos)/2 还原为裸 cosine");
        assertEquals(0.5, results.get(1).getScore(), 1e-9);
        assertEquals(0.0, results.get(2).getScore(), 1e-9);

        RecordedRequest search = stub.searchRequests().get(0);
        JsonNode body = MAPPER.readTree(search.body);
        assertEquals(3, body.path("size").asInt());
        JsonNode filters = body.path("knn").path("filter").path("bool").path("filter");
        assertEquals("legal", filters.get(0).path("term").path("namespace").asText(),
                "namespace 强制过滤（越权红线），不依赖调用方自觉");
    }

    @Test
    void shouldTranslateMetadataPredicatesAndApplyMinScore() throws Exception {
        stub.knnResponse = hits(
                hit("doc1", "legal", "高分", 1.0),
                hit("doc2", "legal", "低分", 0.6));
        ElasticsearchVectorStore store = newStore();

        SearchQuery query = SearchQuery.of("legal", new float[]{1f, 0f}, 5)
                .withFilter(SearchFilter.of(
                        SearchFilter.Predicate.eq("lang", "zh"),
                        SearchFilter.Predicate.gte("year", 2020),
                        SearchFilter.Predicate.exists("clause")))
                .withMinScore(0.5);
        List<VectorRecord> results = store.search(query);

        assertEquals(1, results.size(), "minScore=0.5：cosine 0.2（_score 0.6）被过滤");
        assertEquals("doc1", results.get(0).getId());

        JsonNode filters = MAPPER.readTree(stub.searchRequests().get(0).body)
                .path("knn").path("filter").path("bool").path("filter");
        assertEquals("zh", filters.get(1).path("term").path("metadata.lang").asText());
        assertEquals(2020, filters.get(2).path("range").path("metadata.year").path("gte").asInt());
        assertEquals("metadata.clause", filters.get(3).path("exists").path("field").asText());
    }

    // —— 混合检索（DD20）——

    @Test
    void shouldSupportHybridCapability() throws Exception {
        assertTrue(newStore().supportsHybrid());
    }

    @Test
    void shouldFuseBm25AndKnnClientSideByDefault() throws Exception {
        stub.bm25Response = hits(
                hit("A", "legal", "词面第一", 5.0),
                hit("B", "legal", "词面第二", 3.0));
        stub.knnResponse = hits(
                hit("B", "legal", "语义第一", 1.0),
                hit("C", "legal", "语义第二", 0.75));
        ElasticsearchVectorStore store = newStore();

        List<VectorRecord> fused = store.hybridSearch(
                HybridQuery.of("legal", "合同", new float[]{1f, 0f}, 3));

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList(),
                "RRF：B=1/62+1/61 > A=1/61 > C=1/62");
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(0).getScore(), 1e-9);
        List<RecordedRequest> searches = stub.searchRequests();
        assertEquals(2, searches.size(), "DD20 缺省：两查询 + 客户端融合（免授权）");
        assertTrue(searches.get(0).body.contains("\"match\""));
        assertTrue(searches.get(1).body.contains("\"knn\""));
        assertFalse(stub.requests.stream().anyMatch(r -> r.body != null && r.body.contains("retriever")),
                "native-rrf=false 绝不下发 retriever 请求");
    }

    @Test
    void shouldUseNativeRrfRetrieverWhenEnabledAndLicensed() throws Exception {
        stub.nativeStatus = 200;
        stub.nativeResponse = hits(hit("B", "legal", "融合第一", 0.032));
        ElasticsearchVectorStore store = newStore(true, "", null, null);

        List<VectorRecord> fused = store.hybridSearch(
                HybridQuery.of("legal", "合同", new float[]{1f, 0f}, 3));

        assertEquals(1, fused.size());
        assertEquals("B", fused.get(0).getId());
        List<RecordedRequest> searches = stub.searchRequests();
        assertEquals(1, searches.size(), "授权路径：单请求 retriever.rrf");
        JsonNode rrf = MAPPER.readTree(searches.get(0).body).path("retriever").path("rrf");
        assertEquals(60, rrf.path("rank_constant").asInt());
        assertEquals(3, rrf.path("rank_window_size").asInt());
        assertEquals(2, rrf.path("retrievers").size());
    }

    @Test
    void shouldDegradeToClientSideFusionWhenNativeRrfUnauthorized() throws Exception {
        stub.nativeStatus = 400; // 模拟未授权（Platinum+ 限制）
        stub.bm25Response = hits(hit("A", "legal", "词面", 5.0));
        stub.knnResponse = hits(hit("A", "legal", "语义", 1.0), hit("B", "legal", "语义二", 0.75));
        ElasticsearchVectorStore store = newStore(true, "", null, null);

        List<VectorRecord> fused = store.hybridSearch(
                HybridQuery.of("legal", "合同", new float[]{1f, 0f}, 3));

        assertEquals(List.of("A", "B"), fused.stream().map(VectorRecord::getId).toList(),
                "原生失败自动降级客户端融合（P10），不中断");
        assertEquals(3, stub.searchRequests().size(), "1 次失败的 retriever + 2 次降级查询");
    }

    @Test
    void shouldFuseWeightedWhenFusionModeWeighted() throws Exception {
        stub.bm25Response = hits(
                hit("A", "legal", "词面第一", 2.0),
                hit("B", "legal", "词面第二", 1.0));
        stub.knnResponse = hits(
                hit("B", "legal", "语义第一", 0.9),
                hit("C", "legal", "语义第二", 0.1));
        ElasticsearchVectorStore store = newStore();

        List<VectorRecord> fused = store.hybridSearch(HybridQuery
                .of("legal", "合同", new float[]{1f, 0f}, 3)
                .withFusion(FusionMode.WEIGHTED, 60, 0.3));

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList(),
                "weighted：B=0.7*1.0+0.3*0.0 > A=0.3*1.0 > C=0");
        assertEquals(0.7, fused.get(0).getScore(), 1e-9);
    }

    // —— 融合纯函数（离线可测，无 HTTP）——

    @Test
    void shouldComputeRrfFusionDeterministically() {
        List<VectorRecord> lexical = List.of(record("ns", "A", "a"), record("ns", "B", "b"));
        List<VectorRecord> semantic = List.of(record("ns", "B", "b"), record("ns", "C", "c"));

        List<VectorRecord> fused = ElasticsearchVectorStore.fuseRrf(List.of(lexical, semantic), 60, 10);

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList());
        assertEquals(1.0 / 61, fused.get(1).getScore(), 1e-9);
        assertEquals(1.0 / 62, fused.get(2).getScore(), 1e-9);
        assertEquals(2, ElasticsearchVectorStore.fuseRrf(List.of(lexical, semantic), 60, 2).size(),
                "topK 截断");
    }

    @Test
    void shouldComputeWeightedFusionWithMinMaxNormalization() {
        List<VectorRecord> lexical = List.of(
                record("ns", "A", "a").toBuilder().score(2.0).build(),
                record("ns", "B", "b").toBuilder().score(1.0).build());
        List<VectorRecord> semantic = List.of(
                record("ns", "B", "b").toBuilder().score(0.9).build(),
                record("ns", "C", "c").toBuilder().score(0.1).build());

        List<VectorRecord> fused = ElasticsearchVectorStore.fuseWeighted(lexical, semantic, 0.3, 10);

        assertEquals(List.of("B", "A", "C"), fused.stream().map(VectorRecord::getId).toList());
        assertEquals(0.7, fused.get(0).getScore(), 1e-9, "B = 0.7*1.0(语义归一) + 0.3*0.0(词面归一)");
        assertEquals(0.3, fused.get(1).getScore(), 1e-9);
        assertEquals(0.0, fused.get(2).getScore(), 1e-9);
    }

    // —— 桩基础设施 ——

    private static String hits(String... hitJsons) {
        return "{\"hits\":{\"total\":{\"value\":" + hitJsons.length + "},\"hits\":["
                + String.join(",", hitJsons) + "]}}";
    }

    private static String hit(String id, String namespace, String content, double score) {
        return "{\"_id\":\"" + namespace + "%3A%3A" + id + "\",\"_score\":" + score
                + ",\"_source\":{\"id\":\"" + id + "\",\"namespace\":\"" + namespace
                + "\",\"content\":\"" + content + "\",\"metadata\":{\"lang\":\"zh\"}}}";
    }

    private static final class RecordedRequest {
        final String method;
        final String path;
        final String body;
        final String authorization;
        final String contentType;

        RecordedRequest(String method, String path, String body, String authorization, String contentType) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.authorization = authorization;
            this.contentType = contentType;
        }
    }

    private final class Stub implements com.sun.net.httpserver.HttpHandler {
        final List<RecordedRequest> requests = new ArrayList<>();
        volatile String knnResponse = hits();
        volatile String bm25Response = hits();
        volatile String nativeResponse = hits();
        volatile int nativeStatus = 400;
        volatile int deleteStatus = 200;

        List<RecordedRequest> searchRequests() {
            return requests.stream().filter(r -> r.path.endsWith("_search")).toList();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(method, path, body,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));

            String response;
            int status = 200;
            if (method.equals("PUT") && path.equals("/" + INDEX)) {
                response = "{\"acknowledged\":true}";
            } else if (method.equals("PUT")) {
                response = "{\"result\":\"created\"}";
            } else if (method.equals("DELETE")) {
                status = deleteStatus;
                response = status == 404 ? "{\"result\":\"not_found\"}" : "{\"result\":\"deleted\"}";
            } else if (method.equals("POST") && path.equals("/_bulk")) {
                response = "{\"errors\":false,\"items\":[]}";
            } else if (method.equals("POST") && path.endsWith("_search")) {
                if (body.contains("\"retriever\"")) {
                    status = nativeStatus;
                    response = status == 200 ? nativeResponse
                            : "{\"error\":{\"type\":\"security_exception\"},\"status\":400}";
                } else if (body.contains("\"knn\"")) {
                    response = knnResponse;
                } else {
                    response = bm25Response;
                }
            } else {
                status = 404;
                response = "{\"error\":\"not_found\"}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
