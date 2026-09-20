package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C 组件 L4 - pgvector 生产实现（§12.2）。
 * <p>基于 PostgreSQL + pgvector：{@code vector} 类型 + cosine 距离算子 {@code <=>} + HNSW 索引。
 * 独立数据源（{@code vectorJdbcTemplate}）与业务主库分离。仅在 {@code langur.vector.store=pgvector} 时装配。</p>
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

    private final JdbcTemplate vectorJdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgVectorStore(JdbcTemplate vectorJdbcTemplate, ObjectMapper objectMapper) {
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
