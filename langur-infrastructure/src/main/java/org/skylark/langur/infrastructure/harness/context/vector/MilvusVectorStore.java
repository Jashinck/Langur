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
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * C 组件 L4 - Milvus 向量库实现（H14.6，DD16/DD17/DD18）。
 * <p>RESTful v2 直连（WebClient）而非官方 SDK（DD16 偏差，理由见 RoadMap 2.1 §10）：
 * collection schema 为 {@code id}(PK) / {@code namespace}(partition-key，DD18 越权红线) / {@code content}
 * (enable_match) / {@code metadata}(JSON) / {@code embedding}(FLOAT_VECTOR, HNSW, COSINE) /
 * {@code sparse}(SPARSE_FLOAT_VECTOR，Milvus 2.5+ BM25 function 由 content 自动生成)。</p>
 * <p>混合检索：RESTful v2 未暴露服务端 {@code hybrid_search}（RRFRanker/WeightedRanker 属 SDK 能力），
 * 故 {@link #hybridSearch} 走 dense + sparse(BM25 全文) 两通道 {@code entities/search} + 客户端融合
 * （{@link ClientSideFusion}，与 ES DD20 缺省路径同构）；sparse 通道不可用（无 BM25 function / 旧版本）
 * 时异常上抛，由 {@code VectorMemoryService} 捕获回退 search + 应用侧 {@code EmbeddingRerankPort}（P10 不中断）。
 * store 不可用 fail-fast，绝不静默转内存。</p>
 * <p>安全：{@code password-ref} 经 {@link SecretResolver} 解析（缺 KMS fail-closed），仅注入
 * {@code Bearer user:pass} 认证头，绝不落日志/异常消息；端点经 {@link SsrfGuard} 校验（scheme 限 http/https；
 * 管理侧配置 host 即显式白名单——URI 来自受信配置而非模型输出）。</p>
 * <p>仅在 {@code langur.vector.store=milvus} 时装配（P9），默认 memory 行为不变。</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "langur.vector.store", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String DENSE_FIELD = "embedding";
    private static final String SPARSE_FIELD = "sparse";

    private final VectorProperties properties;
    private final String collection;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String authHeader;
    private final AtomicBoolean collectionEnsured = new AtomicBoolean(false);

    @Autowired
    public MilvusVectorStore(VectorProperties properties,
                             ObjectMapper objectMapper,
                             SsrfGuard ssrfGuard,
                             ObjectProvider<SecretResolver> secretResolverProvider) {
        this(properties, objectMapper, ssrfGuard, secretResolverProvider.getIfAvailable());
    }

    public MilvusVectorStore(VectorProperties properties,
                             ObjectMapper objectMapper,
                             SsrfGuard ssrfGuard,
                             SecretResolver secretResolver) {
        this.properties = properties;
        this.collection = properties.getMilvus().getCollection();
        this.objectMapper = objectMapper;
        this.authHeader = buildAuthHeader(properties.getMilvus(), secretResolver);
        URI base = validateBase(properties.getMilvus().getUri(), ssrfGuard);
        this.webClient = WebClient.builder().baseUrl(base.toString()).build();
    }

    /** Bearer 认证头（可空）；password-ref 配置了却解析不出明文 → fail-closed，绝不静默匿名。 */
    private static String buildAuthHeader(VectorProperties.Milvus milvusConfig, SecretResolver secretResolver) {
        if (StringUtils.isBlank(milvusConfig.getUsername())) {
            return null;
        }
        String password = null;
        if (StringUtils.isNotBlank(milvusConfig.getPasswordRef())) {
            if (secretResolver == null) {
                throw new IllegalStateException(
                        "no SecretResolver available for milvus password-ref (fail-closed)");
            }
            password = secretResolver.resolve(milvusConfig.getPasswordRef())
                    .filter(StringUtils::isNotBlank)
                    .orElseThrow(() -> new IllegalStateException(
                            "cannot resolve milvus password-ref (fail-closed)"));
        }
        String credential = milvusConfig.getUsername() + ":" + StringUtils.defaultString(password);
        // Milvus RESTful v2 约定：Authorization: Bearer <username>:<password>（明文令牌，仅注入请求头）
        return "Bearer " + credential;
    }

    private static URI validateBase(String uri, SsrfGuard ssrfGuard) {
        URI base = URI.create(StringUtils.defaultIfBlank(uri, "http://localhost:19530").trim());
        ssrfGuard.validate(base, List.of(base.getHost()));
        return base;
    }

    // —— 写入 ——

    @Override
    public void upsert(VectorRecord record) {
        ensureCollection(record.getVector() != null ? record.getVector().length : 0);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        body.putArray("data").add(entity(record));
        post("/v2/vectordb/entities/upsert", body);
    }

    @Override
    public void upsertAll(String namespace, List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        records.stream().findFirst().ifPresent(r ->
                ensureCollection(r.getVector() != null ? r.getVector().length : 0));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        ArrayNode data = body.putArray("data");
        records.forEach(record -> data.add(entity(record)));
        post("/v2/vectordb/entities/upsert", body);
    }

    @Override
    public void delete(String namespace, String id) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        body.put("filter", "namespace == " + quote(namespace) + " && id == " + quote(id));
        post("/v2/vectordb/entities/delete", body);
    }

    /** 首次写入前 best-effort 建 collection（已存在/无权限时忽略，交由预置 schema）。 */
    private void ensureCollection(int dimsFromRecord) {
        if (!collectionEnsured.compareAndSet(false, true)) {
            return;
        }
        int dims = properties.getDimension() > 0 ? properties.getDimension() : dimsFromRecord;
        if (dims <= 0) {
            return;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("collectionName", collection);
            ObjectNode schema = body.putObject("schema");
            schema.put("autoID", false);
            ArrayNode fields = schema.putArray("fields");

            ObjectNode id = field("id", "VarChar");
            id.put("isPrimary", true);
            id.putObject("elementTypeParams").put("max_length", "512");
            fields.add(id);

            ObjectNode namespace = field("namespace", "VarChar");
            namespace.put("isPartitionKey", true);
            namespace.putObject("elementTypeParams").put("max_length", "256");
            fields.add(namespace);

            ObjectNode content = field("content", "VarChar");
            ObjectNode contentParams = content.putObject("elementTypeParams");
            contentParams.put("max_length", "65535");
            contentParams.put("enable_match", "true");
            fields.add(content);

            fields.add(field("metadata", "JSON"));

            ObjectNode embedding = field(DENSE_FIELD, "FloatVector");
            embedding.putObject("elementTypeParams").put("dim", String.valueOf(dims));
            fields.add(embedding);

            fields.add(field(SPARSE_FIELD, "SparseFloatVector"));

            // Milvus 2.5+ BM25 function：insert 时由 content 自动生成 sparse（原生全文/混合通道）
            ObjectNode function = schema.putArray("functions").addObject();
            function.put("name", "bm25");
            function.put("type", "BM25");
            function.putArray("inputFieldNames").add("content");
            function.putArray("outputFieldNames").add(SPARSE_FIELD);

            ArrayNode indexParams = body.putArray("indexParams");
            ObjectNode denseIndex = indexParams.addObject();
            denseIndex.put("fieldName", DENSE_FIELD);
            denseIndex.put("indexType", "HNSW");
            denseIndex.put("metricType", "COSINE");
            ObjectNode sparseIndex = indexParams.addObject();
            sparseIndex.put("fieldName", SPARSE_FIELD);
            sparseIndex.put("indexType", "SPARSE_INVERTED_INDEX");
            sparseIndex.put("metricType", "BM25");

            post("/v2/vectordb/collections/create", body);
        } catch (Exception e) {
            log.warn("[Vector][Milvus] collection ensure failed (assume pre-provisioned): {}", e.getMessage());
        }
    }

    private ObjectNode field(String name, String dataType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("fieldName", name);
        node.put("dataType", dataType);
        return node;
    }

    private ObjectNode entity(VectorRecord record) {
        ObjectNode entity = objectMapper.createObjectNode();
        entity.put("id", record.getId());
        entity.put("namespace", record.getNamespace());
        entity.put("content", record.getContent());
        entity.set("metadata", objectMapper.valueToTree(
                record.getMetadata() != null ? record.getMetadata() : Map.of()));
        ArrayNode vector = entity.putArray(DENSE_FIELD);
        if (record.getVector() != null) {
            for (float v : record.getVector()) {
                vector.add(v);
            }
        }
        return entity;
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
        List<VectorRecord> hits = denseSearch(query.getNamespace(), query.getQueryVector(),
                query.getFilter(), query.getTopK());
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

    /**
     * dense + sparse(BM25 全文) 两通道 + 客户端融合（DD17；服务端 ranker 下推属 SDK 能力，REST 未暴露）。
     * sparse 通道失败异常上抛 → domain 回退应用侧 rerank（P10）。
     */
    @Override
    public List<VectorRecord> hybridSearch(HybridQuery query) {
        if (query.getTopK() <= 0) {
            return List.of();
        }
        List<VectorRecord> semantic = denseSearch(query.getNamespace(), query.getQueryVector(),
                query.getFilter(), query.getTopK());
        List<VectorRecord> lexical = sparseSearch(query.getNamespace(), query.getQueryText(),
                query.getFilter(), query.getTopK());
        if (query.getFusion() == FusionMode.WEIGHTED) {
            return ClientSideFusion.fuseWeighted(lexical, semantic, query.getLexicalWeight(), query.getTopK());
        }
        return ClientSideFusion.fuseRrf(List.of(lexical, semantic), query.getRrfK(), query.getTopK());
    }

    private List<VectorRecord> denseSearch(String namespace, float[] queryVector,
                                           SearchFilter filter, int topK) {
        ObjectNode body = searchBody(namespace, filter, topK, DENSE_FIELD);
        ArrayNode data = body.putArray("data").addArray();
        if (queryVector != null) {
            for (float v : queryVector) {
                data.add(v);
            }
        }
        return parseHits(post("/v2/vectordb/entities/search", body));
    }

    /** Milvus 2.5+ BM25 function：sparse 通道直接传查询文本（服务端全文匹配打分）。 */
    private List<VectorRecord> sparseSearch(String namespace, String queryText,
                                            SearchFilter filter, int topK) {
        ObjectNode body = searchBody(namespace, filter, topK, SPARSE_FIELD);
        body.putArray("data").add(StringUtils.defaultString(queryText));
        return parseHits(post("/v2/vectordb/entities/search", body));
    }

    private ObjectNode searchBody(String namespace, SearchFilter filter, int topK, String annsField) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        body.put("annsField", annsField);
        body.put("limit", topK);
        body.putArray("outputFields").add("id").add("namespace").add("content").add("metadata");
        body.put("filter", filterExpr(namespace, filter));
        return body;
    }

    // —— 布尔表达式（namespace 强制过滤 + SearchFilter 谓词翻译）——

    /** namespace 为 partition-key 强制过滤（越权红线）；谓词翻译为 Milvus 布尔表达式。 */
    static String filterExpr(String namespace, SearchFilter filter) {
        StringBuilder expr = new StringBuilder("namespace == ").append(quote(namespace));
        if (filter != null) {
            for (SearchFilter.Predicate predicate : filter.getPredicates()) {
                expr.append(" && ").append(predicateExpr(predicate));
            }
        }
        return expr.toString();
    }

    private static String predicateExpr(SearchFilter.Predicate predicate) {
        String path = "metadata[\"" + escape(predicate.field()) + "\"]";
        return switch (predicate.operator()) {
            case EQ -> path + " == " + literal(predicate.value());
            case IN -> {
                StringBuilder in = new StringBuilder(path + " in [");
                if (predicate.value() instanceof Collection<?> values) {
                    boolean first = true;
                    for (Object candidate : values) {
                        if (!first) {
                            in.append(", ");
                        }
                        in.append(literal(candidate));
                        first = false;
                    }
                }
                yield in.append(']').toString();
            }
            case GT -> path + " > " + literal(predicate.value());
            case LT -> path + " < " + literal(predicate.value());
            case GTE -> path + " >= " + literal(predicate.value());
            case LTE -> path + " <= " + literal(predicate.value());
            case EXISTS -> path + " exists";
        };
    }

    private static String literal(Object value) {
        if (value instanceof String s) {
            return quote(s);
        }
        return String.valueOf(value);
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** 转义反斜杠与双引号，防布尔表达式注入。 */
    private static String escape(String value) {
        return StringUtils.defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // —— 响应解析 ——

    private List<VectorRecord> parseHits(JsonNode data) {
        List<VectorRecord> records = new ArrayList<>();
        for (JsonNode hit : data) {
            Map<String, Object> metadata = hit.has("metadata")
                    ? objectMapper.convertValue(hit.get("metadata"),
                            new TypeReference<HashMap<String, Object>>() {})
                    : new HashMap<>();
            records.add(VectorRecord.builder()
                    .id(hit.path("id").asText())
                    .namespace(hit.path("namespace").asText())
                    .content(hit.path("content").asText(null))
                    .metadata(metadata)
                    // COSINE 度量下 distance 即裸 cosine 相似度，与内存实现同语义
                    .score(hit.path("distance").asDouble(0d))
                    .build());
        }
        return records;
    }

    // —— HTTP ——

    /** POST 并校验 Milvus 响应包络 {@code {code, data}}；code != 0 视为失败（fail-fast，不静默）。 */
    private JsonNode post(String path, ObjectNode body) {
        WebClient.RequestBodySpec spec = webClient.post().uri(path)
                .header("Content-Type", "application/json");
        if (authHeader != null) {
            spec = spec.header("Authorization", authHeader);
        }
        String response;
        try {
            response = spec.bodyValue(body.toString())
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);
        } catch (WebClientResponseException e) {
            // 消息不含 Authorization 头/密码；仅状态码 + 响应体摘要供排查
            throw new IllegalStateException("milvus request failed: " + path
                    + " -> " + e.getStatusCode().value() + " "
                    + StringUtils.abbreviate(e.getResponseBodyAsString(), 200), e);
        }
        try {
            JsonNode root = objectMapper.readTree(StringUtils.defaultString(response));
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("milvus request failed: " + path
                        + " -> code=" + code + " "
                        + StringUtils.abbreviate(root.path("message").asText(""), 200));
            }
            return root.path("data");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("milvus response unparsable: " + path, e);
        }
    }
}
