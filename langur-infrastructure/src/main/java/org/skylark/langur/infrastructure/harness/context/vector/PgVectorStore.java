package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.domain.harness.context.vector.SearchQuery;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C 组件 L4 - pgvector 生产实现（§12.2）。
 * <p>基于 PostgreSQL + pgvector：{@code vector} 类型 + cosine 距离算子 {@code <=>} + HNSW 索引。
 * 独立数据源（{@code vectorJdbcTemplate}，H14.7 由 start 按 {@code langur.vector.pgvector.*} 条件装配）
 * 与业务主库分离。仅在 {@code langur.vector.store=pgvector} 时装配。
 * v2.1 端口补齐（H14.7）：delete / 批量 upsert / {@code search(SearchQuery)}（谓词 + minScore 后过滤）；
 * {@code supportsHybrid()=false}，混合检索走应用侧 {@code EmbeddingRerankPort} 兜底（P10）。</p>
 *
 * <p>建表参考：
 * <pre>
 * CREATE TABLE t_vector_knowledge (
 *   id TEXT NOT NULL,
 *   namespace TEXT NOT NULL,
 *   embedding vector(256),
 *   content TEXT,
 *   metadata JSONB,
 *   PRIMARY KEY (namespace, id)
 * );
 * CREATE INDEX ON t_vector_knowledge USING hnsw (embedding vector_cosine_ops);
 * </pre></p>
 */
@Repository
@ConditionalOnProperty(name = "langur.vector.store", havingValue = "pgvector")
public class PgVectorStore implements VectorStore {

    private static final String UPSERT_SQL = """
            INSERT INTO t_vector_knowledge (id, namespace, embedding, content, metadata)
            VALUES (?, ?, ?::vector, ?, ?::jsonb)
            ON CONFLICT (namespace, id) DO UPDATE
              SET embedding = EXCLUDED.embedding,
                  content   = EXCLUDED.content,
                  metadata  = EXCLUDED.metadata
            """;

    private static final String SEARCH_SQL = """
            SELECT id, namespace, content, metadata, 1 - (embedding <=> ?::vector) AS score
            FROM t_vector_knowledge
            WHERE namespace = ?
            ORDER BY embedding <=> ?::vector ASC
            LIMIT ?
            """;

    private static final String DELETE_SQL = """
            DELETE FROM t_vector_knowledge WHERE namespace = ? AND id = ?
            """;

    /** 谓词/minScore 后过滤时的候选放大系数（SQL 侧仅按 namespace + 向量序召回）。 */
    private static final int OVERFETCH_FACTOR = 5;
    private static final int OVERFETCH_MIN = 50;

    private final JdbcTemplate vectorJdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgVectorStore(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                         ObjectMapper objectMapper) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsert(VectorRecord record) {
        vectorJdbcTemplate.update(UPSERT_SQL,
                record.getId(),
                record.getNamespace(),
                toVectorLiteral(record.getVector()),
                record.getContent(),
                writeMetadata(record.getMetadata()));
    }

    @Override
    public List<VectorRecord> search(String namespace, float[] queryVector, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        String literal = toVectorLiteral(queryVector);
        return vectorJdbcTemplate.query(SEARCH_SQL,
                (rs, rowNum) -> VectorRecord.builder()
                        .id(rs.getString("id"))
                        .namespace(rs.getString("namespace"))
                        .content(rs.getString("content"))
                        .metadata(readMetadata(rs.getString("metadata")))
                        .score(rs.getDouble("score"))
                        .build(),
                literal, namespace, literal, topK);
    }

    // —— v2.1 端口补齐（H14.7）：delete / 批量 / 结构化检索；supportsHybrid 保持 false（应用侧 rerank 兜底）——

    @Override
    public void delete(String namespace, String id) {
        vectorJdbcTemplate.update(DELETE_SQL, namespace, id);
    }

    @Override
    public void upsertAll(String namespace, List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Object[]> batchArgs = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            batchArgs.add(new Object[]{
                    record.getId(),
                    record.getNamespace(),
                    toVectorLiteral(record.getVector()),
                    record.getContent(),
                    writeMetadata(record.getMetadata())});
        }
        vectorJdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs);
    }

    /**
     * 结构化检索（H14.7）：SQL 侧按 namespace + 向量序放大召回候选，{@link org.skylark.langur.domain.harness.context.vector.SearchFilter}
     * 谓词与 minScore 在 store 层后过滤（jsonb 谓词不下推，避免方言差异；过滤语义与内存实现一致，不静默丢弃）。
     */
    @Override
    public List<VectorRecord> search(SearchQuery query) {
        if (query.getTopK() <= 0) {
            return List.of();
        }
        boolean needsPostFilter = !query.getFilter().isEmpty() || query.getMinScore() > 0.0;
        int fetch = needsPostFilter
                ? Math.max(query.getTopK() * OVERFETCH_FACTOR, OVERFETCH_MIN)
                : query.getTopK();
        List<VectorRecord> candidates = search(query.getNamespace(), query.getQueryVector(), fetch);
        if (!needsPostFilter) {
            return candidates;
        }
        double minScore = query.getMinScore();
        return candidates.stream()
                .filter(record -> query.getFilter().matches(record.getMetadata()))
                .filter(record -> minScore <= 0.0 || record.getScore() >= minScore)
                .limit(query.getTopK())
                .toList();
    }

    private String toVectorLiteral(float[] vector) {
        if (vector == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata != null ? metadata : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<HashMap<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
