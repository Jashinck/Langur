package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.skylark.langur.domain.harness.context.vector.FusionMode;
import org.skylark.langur.domain.harness.context.vector.HybridQuery;
import org.skylark.langur.domain.harness.context.vector.SearchFilter;
import org.skylark.langur.domain.harness.context.vector.SearchQuery;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.skylark.langur.infrastructure.harness.tool.rest.SsrfGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * C 组件 L4 - Elasticsearch 向量库实现（H14.5，DD15/DD17/DD18/DD20）。
 * <p>REST 直连（WebClient）而非官方 SDK（DD15 偏差，理由见 RoadMap 2.1 §10）：
 * 单索引 + {@code namespace} keyword 强制过滤（DD18 越权红线）；{@code embedding} dense_vector cosine；
 * 文档 {@code _id = namespace::id} 天然幂等 UPSERT。</p>
 * <p><b>DD20（已核实：RRF retriever 属 Platinum+ 付费授权层）</b>：缺省 {@code native-rrf=false}，
 * 混合检索走"BM25 match + kNN 两查询 + 客户端 RRF 融合"（免授权）；授权环境可开启原生
 * {@code retriever.rrf}，任何原生失败自动降级客户端融合（P10，不中断）。store 不可用 fail-fast，绝不静默转内存。</p>
 * <p>安全：{@code password-ref} 经 {@link SecretResolver} 解析（缺 KMS fail-closed），仅注入 Basic 认证头，
 * 绝不落日志/异常消息；端点 URI 经 {@link SsrfGuard} 校验（scheme 限 http/https；管理侧配置的 host
 * 即显式白名单——URI 来自受信配置而非模型输出，不属 SSRF 攻击面）。</p>
 * <p>仅在 {@code langur.vector.store=elasticsearch} 时装配（P9），默认 memory 行为不变。</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "langur.vector.store", havingValue = "elasticsearch")
public class ElasticsearchVectorStore implements VectorStore {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String EMBEDDING_FIELD = "embedding";
    private static final String NAMESPACE_FIELD = "namespace";

    private final VectorProperties.Elasticsearch esConfig;
    private final VectorProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String authHeader;
    private final boolean nativeRrf;
    private final AtomicBoolean indexEnsured = new AtomicBoolean(false);

    @Autowired
    public ElasticsearchVectorStore(VectorProperties properties,
                                    ObjectMapper objectMapper,
                                    SsrfGuard ssrfGuard,
                                    ObjectProvider<SecretResolver> secretResolverProvider) {
        this(properties, objectMapper, ssrfGuard, secretResolverProvider.getIfAvailable());
    }

    public ElasticsearchVectorStore(VectorProperties properties,
                                    ObjectMapper objectMapper,
                                    SsrfGuard ssrfGuard,
                                    SecretResolver secretResolver) {
        this.properties = properties;
        this.esConfig = properties.getElasticsearch();
        this.objectMapper = objectMapper;
        this.nativeRrf = esConfig.isNativeRrf();
        this.authHeader = buildAuthHeader(esConfig, secretResolver);
        URI base = validateAndPickBase(esConfig.getUris(), ssrfGuard);
        this.webClient = WebClient.builder().baseUrl(base.toString()).build();
    }

    /** Basic 认证头（可空）；password-ref 配置了却解析不出明文 → fail-closed，绝不静默匿名。 */
    private static String buildAuthHeader(VectorProperties.Elasticsearch esConfig, SecretResolver secretResolver) {
        if (StringUtils.isBlank(esConfig.getUsername())) {
            return null;
        }
        String password = null;
        if (StringUtils.isNotBlank(esConfig.getPasswordRef())) {
            password = secretResolver != null
                    ? secretResolver.resolve(esConfig.getPasswordRef())
                        .filter(StringUtils::isNotBlank)
                        .orElseThrow(() -> new IllegalStateException(
                                "cannot resolve elasticsearch password-ref (fail-closed)"))
                    : null;
            if (secretResolver == null) {
                throw new IllegalStateException(
                        "no SecretResolver available for elasticsearch password-ref (fail-closed)");
            }
        }
        String credential = esConfig.getUsername() + ":" + StringUtils.defaultString(password);
        return "Basic " + Base64.getEncoder()
                .encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    /** 逐个校验配置 URI（scheme + 地址段），返回首个作为 base；配置 host 即显式白名单。 */
    private static URI validateAndPickBase(String uris, SsrfGuard ssrfGuard) {
        List<String> configured = List.of(StringUtils.defaultIfBlank(uris, "http://localhost:9200").split(","));
        List<String> allowedHosts = new ArrayList<>();
        for (String raw : configured) {
            allowedHosts.add(URI.create(raw.trim()).getHost());
        }
        URI base = null;
        for (String raw : configured) {
            URI uri = URI.create(raw.trim());
            ssrfGuard.validate(uri, allowedHosts);
            if (base == null) {
                base = uri;
            }
        }
        return base;
    }

    // —— 写入 ——

    @Override
    public void upsert(VectorRecord record) {
        ensureIndex(record.getVector() != null ? record.getVector().length : 0);
        exchange(HttpMethod.PUT, docPath(record.getNamespace(), record.getId()), docBody(record), "application/json");
    }

    @Override
    public void upsertAll(String namespace, List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        records.stream().findFirst().ifPresent(r ->
                ensureIndex(r.getVector() != null ? r.getVector().length : 0));
        StringBuilder ndjson = new StringBuilder();
        for (VectorRecord record : records) {
            ndjson.append("{\"index\":{\"_index\":\"").append(properties.getElasticsearch().getIndex())
                    .append("\",\"_id\":\"").append(docId(record.getNamespace(), record.getId()))
                    .append("\"}}\n")
                    .append(docBody(record)).append('\n');
        }
        String response = exchange(HttpMethod.POST, "/_bulk", ndjson.toString(), "application/x-ndjson");
        try {
            if (objectMapper.readTree(response).path("errors").asBoolean(false)) {
                throw new IllegalStateException("elasticsearch bulk upsert reported item errors");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("elasticsearch bulk response unparsable", e);
        }
    }

    @Override
    public void delete(String namespace, String id) {
        try {
            exchange(HttpMethod.DELETE, docPath(namespace, id), null, null);
        } catch (IllegalStateException e) {
            // 幂等删除：目标不存在视为成功（exchange 将 HTTP 错误包装为 IllegalStateException）
            if (!(e.getCause() instanceof WebClientResponseException.NotFound)) {
                throw e;
            }
        }
    }

    /** 首次写入前 best-effort 建索引（已存在/无权限建索引时忽略，交由预置 mapping）。 */
    private void ensureIndex(int dimsFromRecord) {
        if (!indexEnsured.compareAndSet(false, true)) {
            return;
        }
        int dims = properties.getDimension() > 0 ? properties.getDimension() : dimsFromRecord;
        if (dims <= 0) {
            return;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode props = body.putObject("mappings").putObject("properties");
            props.putObject("id").put("type", "keyword");
            props.putObject(NAMESPACE_FIELD).put("type", "keyword");
            props.putObject("content").put("type", "text");
            props.putObject("metadata").put("type", "object");
            ObjectNode embedding = props.putObject(EMBEDDING_FIELD);
            embedding.put("type", "dense_vector");
            embedding.put("dims", dims);
            embedding.put("index", true);
            embedding.put("similarity", "cosine");
            exchange(HttpMethod.PUT, "/" + properties.getElasticsearch().getIndex(),
                    body.toString(), "application/json");
        } catch (Exception e) {
            log.warn("[Vector][ES] index ensure failed (assume pre-provisioned): {}", e.getMessage());
        }
    }

    private String docBody(VectorRecord record) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", record.getId());
        body.put(NAMESPACE_FIELD, record.getNamespace());
        body.put("content", record.getContent());
        body.set("metadata", objectMapper.valueToTree(
                record.getMetadata() != null ? record.getMetadata() : Map.of()));
        ArrayNode vector = body.putArray(EMBEDDING_FIELD);
        if (record.getVector() != null) {
            for (float v : record.getVector()) {
                vector.add(v);
            }
        }
        return body.toString();
    }

    // —— 检索 ——

    @Override
    public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
        return search(SearchQuery.of(namespace, queryVector, topK));
    }

    @Override
    public List<VectorRecord> search(SearchQuery query) {
        if (query.getTopK() <= 0) {
            return List.of();
        }
        String response = exchange(HttpMethod.POST, searchPath(),
                knnBody(query.getNamespace(), query.getQueryVector(), query.getFilter(), query.getTopK()).toString(),
                "application/json");
        // ES cosine _score = (1 + cos) / 2，还原为裸 cosine 保持与内存实现同语义（负分合法）
        List<VectorRecord> hits = parseHits(response, true);
        double minScore = query.getMinScore();
        if (minScore <= 0.0) {
            return hits;
        }
        return hits.stream().filter(r -> r.getScore() >= minScore).toList();
    }

    @Override
    public boolean supportsHybrid() {
        return true;
    }

    @Override
    public List<VectorRecord> hybridSearch(HybridQuery query) {
        if (query.getTopK() <= 0) {
            return List.of();
        }
        if (nativeRrf) {
            try {
                return nativeRrfSearch(query);
            } catch (RuntimeException e) {
                // DD20：未授权（400/403）或版本不支持 → 自动降级客户端融合（P10，不中断）
                log.warn("[Vector][ES] native rrf retriever failed, degrade to client-side fusion: {}",
                        e.getMessage());
            }
        }
        return clientSideFusion(query);
    }

    /** 原生 {@code retriever.rrf}（仅授权层可用，DD20 缺省关闭）。 */
    private List<VectorRecord> nativeRrfSearch(HybridQuery query) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode rrf = body.putObject("retriever").putObject("rrf");
        ArrayNode retrievers = rrf.putArray("retrievers");
        retrievers.addObject().putObject("standard")
                .set("query", bm25Query(query.getNamespace(), query.getQueryText(), query.getFilter()));
        ObjectNode knn = retrievers.addObject().putObject("knn");
        knn.put("field", EMBEDDING_FIELD);
        knn.set("query_vector", vectorNode(query.getQueryVector()));
        knn.put("k", query.getTopK());
        knn.put("num_candidates", Math.max(query.getTopK() * 10, 100));
        knn.set("filter", namespaceFilter(query.getNamespace(), query.getFilter()));
        rrf.put("rank_window_size", query.getTopK());
        rrf.put("rank_constant", query.getRrfK());
        String response = exchange(HttpMethod.POST, searchPath(), body.toString(), "application/json");
        return parseHits(response, false);
    }

    /** DD20 缺省路径：BM25 + kNN 两查询，客户端融合（RRF / WEIGHTED）。 */
    private List<VectorRecord> clientSideFusion(HybridQuery query) {
        ObjectNode bm25Body = objectMapper.createObjectNode();
        bm25Body.put("size", query.getTopK());
        excludeEmbedding(bm25Body);
        bm25Body.set("query", bm25Query(query.getNamespace(), query.getQueryText(), query.getFilter()));
        List<VectorRecord> lexical = parseHits(
                exchange(HttpMethod.POST, searchPath(), bm25Body.toString(), "application/json"), false);

        String knnResponse = exchange(HttpMethod.POST, searchPath(),
                knnBody(query.getNamespace(), query.getQueryVector(), query.getFilter(), query.getTopK()).toString(),
                "application/json");
        List<VectorRecord> semantic = parseHits(knnResponse, true);

        if (query.getFusion() == FusionMode.WEIGHTED) {
            return fuseWeighted(lexical, semantic, query.getLexicalWeight(), query.getTopK());
        }
        return fuseRrf(List.of(lexical, semantic), query.getRrfK(), query.getTopK());
    }

    // —— 融合（纯函数，可离线确定性单测）——

    /**
     * RRF 融合（DD17）：score(d) = Σ_lists 1 / (k + rank)，rank 从 1 起；免归一化、跨检索器稳健。
     * 并列时按 key 字典序保证确定性。
     */
    static List<VectorRecord> fuseRrf(List<List<VectorRecord>> rankedLists, int rrfK, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, VectorRecord> firstSeen = new LinkedHashMap<>();
        for (List<VectorRecord> ranked : rankedLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                VectorRecord record = ranked.get(rank);
                String key = record.getNamespace() + "::" + record.getId();
                scores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
                firstSeen.putIfAbsent(key, record);
            }
        }
        return topByFusedScore(scores, firstSeen, topK);
    }

    /**
     * 加权融合（WEIGHTED）：各列表分数 min-max 归一后按 {@code lexicalWeight} 混合：
     * final = (1 - w) * normSemantic + w * normLexical；单值列表归一为 1.0。
     */
    static List<VectorRecord> fuseWeighted(List<VectorRecord> lexical, List<VectorRecord> semantic,
                                           double lexicalWeight, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, VectorRecord> firstSeen = new LinkedHashMap<>();
        accumulateNormalized(scores, firstSeen, semantic, 1.0 - lexicalWeight);
        accumulateNormalized(scores, firstSeen, lexical, lexicalWeight);
        return topByFusedScore(scores, firstSeen, topK);
    }

    private static void accumulateNormalized(Map<String, Double> scores, Map<String, VectorRecord> firstSeen,
                                             List<VectorRecord> ranked, double weight) {
        if (ranked.isEmpty()) {
            return;
        }
        double max = ranked.stream().mapToDouble(VectorRecord::getScore).max().orElse(0d);
        double min = ranked.stream().mapToDouble(VectorRecord::getScore).min().orElse(0d);
        double span = max - min;
        for (VectorRecord record : ranked) {
            String key = record.getNamespace() + "::" + record.getId();
            double normalized = span <= 0d ? 1.0 : (record.getScore() - min) / span;
            scores.merge(key, weight * normalized, Double::sum);
            firstSeen.putIfAbsent(key, record);
        }
    }

    private static List<VectorRecord> topByFusedScore(Map<String, Double> scores,
                                                      Map<String, VectorRecord> firstSeen, int topK) {
        List<VectorRecord> fused = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(topK, 0))
                .forEach(entry -> fused.add(firstSeen.get(entry.getKey()).toBuilder()
                        .score(entry.getValue())
                        .build()));
        return fused;
    }

    // —— 查询构造 ——

    private ObjectNode knnBody(String namespace, float[] queryVector, SearchFilter filter, int topK) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", topK);
        excludeEmbedding(body);
        ObjectNode knn = body.putObject("knn");
        knn.put("field", EMBEDDING_FIELD);
        knn.set("query_vector", vectorNode(queryVector));
        knn.put("k", topK);
        knn.put("num_candidates", Math.max(topK * 10, 100));
        knn.set("filter", namespaceFilter(namespace, filter));
        return body;
    }

    /** namespace 强制过滤（越权红线）+ {@link SearchFilter} 谓词翻译（EQ/IN/GT/LT/GTE/LTE/EXISTS）。 */
    private ObjectNode namespaceFilter(String namespace, SearchFilter filter) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.addObject().putObject("term").put(NAMESPACE_FIELD, namespace);
        if (filter != null) {
            for (SearchFilter.Predicate predicate : filter.getPredicates()) {
                filters.add(predicateQuery(predicate));
            }
        }
        return root;
    }

    private ObjectNode predicateQuery(SearchFilter.Predicate predicate) {
        String field = "metadata." + predicate.field();
        ObjectNode node = objectMapper.createObjectNode();
        switch (predicate.operator()) {
            case EQ -> node.putObject("term").set(field, objectMapper.valueToTree(predicate.value()));
            case IN -> node.putObject("terms").set(field, objectMapper.valueToTree(predicate.value()));
            case GT, LT, GTE, LTE -> {
                String op = switch (predicate.operator()) {
                    case GT -> "gt";
                    case LT -> "lt";
                    case GTE -> "gte";
                    default -> "lte";
                };
                node.putObject("range").putObject(field).set(op, objectMapper.valueToTree(predicate.value()));
            }
            case EXISTS -> node.putObject("exists").put("field", field);
        }
        return node;
    }

    private ObjectNode bm25Query(String namespace, String queryText, SearchFilter filter) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.addObject().putObject("match").put("content", StringUtils.defaultString(queryText));
        bool.set("filter", namespaceFilter(namespace, filter).get("bool").get("filter"));
        return root;
    }

    private ArrayNode vectorNode(float[] vector) {
        ArrayNode node = objectMapper.createArrayNode();
        if (vector != null) {
            for (float v : vector) {
                node.add(v);
            }
        }
        return node;
    }

    private void excludeEmbedding(ObjectNode body) {
        body.putObject("_source").putArray("excludes").add(EMBEDDING_FIELD);
    }

    private String searchPath() {
        return "/" + properties.getElasticsearch().getIndex() + "/_search";
    }

    private String docPath(String namespace, String id) {
        return "/" + properties.getElasticsearch().getIndex() + "/_doc/" + docId(namespace, id);
    }

    /** {@code _id = namespace::id}（DD18 单索引 + namespace），URL 编码防路径注入。 */
    private static String docId(String namespace, String id) {
        return URLEncoder.encode(namespace + "::" + id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // —— 响应解析 ——

    private List<VectorRecord> parseHits(String response, boolean cosineTransform) {
        List<VectorRecord> records = new ArrayList<>();
        try {
            JsonNode hits = objectMapper.readTree(StringUtils.defaultString(response)).path("hits").path("hits");
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                double score = hit.path("_score").asDouble(0d);
                if (cosineTransform) {
                    score = 2d * score - 1d;
                }
                Map<String, Object> metadata = source.has("metadata")
                        ? objectMapper.convertValue(source.get("metadata"),
                                new TypeReference<HashMap<String, Object>>() {})
                        : new HashMap<>();
                float[] vector = null;
                if (source.has(EMBEDDING_FIELD) && source.get(EMBEDDING_FIELD).isArray()) {
                    vector = new float[source.get(EMBEDDING_FIELD).size()];
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] = (float) source.get(EMBEDDING_FIELD).get(i).asDouble();
                    }
                }
                records.add(VectorRecord.builder()
                        .id(source.path("id").asText(hit.path("_id").asText()))
                        .namespace(source.path(NAMESPACE_FIELD).asText())
                        .content(source.path("content").asText(null))
                        .metadata(metadata)
                        .vector(vector)
                        .score(score)
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("elasticsearch search response unparsable", e);
        }
        return records;
    }

    // —— HTTP ——

    private String exchange(HttpMethod method, String path, String body, String contentType) {
        WebClient.RequestBodySpec spec = webClient.method(method).uri(path);
        if (authHeader != null) {
            spec = spec.header("Authorization", authHeader);
        }
        if (contentType != null) {
            spec = spec.header("Content-Type", contentType);
        }
        WebClient.RequestHeadersSpec<?> ready = body != null ? spec.bodyValue(body) : spec;
        try {
            String response = ready.retrieve().bodyToMono(String.class).block(TIMEOUT);
            return StringUtils.defaultString(response);
        } catch (WebClientResponseException e) {
            // 消息不含 Authorization 头/密码；仅状态码 + 响应体摘要供排查
            throw new IllegalStateException("elasticsearch request failed: " + method + " " + path
                    + " -> " + e.getStatusCode().value() + " "
                    + StringUtils.abbreviate(e.getResponseBodyAsString(), 200), e);
        }
    }
}
