package org.skylark.langur.infrastructure.harness.context.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.skylark.langur.domain.harness.context.vector.SearchFilter;
import org.skylark.langur.domain.harness.context.vector.SearchQuery;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H14.7 验收 - {@link PgVectorStore} v2.1 端口补齐离线单测（Mockito mock JdbcTemplate，不依赖真实 PG）：
 * delete SQL、批量 upsert 走 JDBC batch、{@code search(SearchQuery)} 候选放大 + 谓词/minScore 后过滤
 * （jsonb 谓词不下推，语义与内存实现一致，不静默丢弃）、{@code supportsHybrid()=false}（应用侧兜底）。
 */
class PgVectorStorePortTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PgVectorStore store = new PgVectorStore(jdbcTemplate, new ObjectMapper());

    private static VectorRecord record(String id, double score, Map<String, Object> metadata) {
        return VectorRecord.builder()
                .id(id).namespace("legal").content(id).metadata(metadata).score(score).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSearchPlainQueryWithoutPostFilter() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(record("d1", 0.9, Map.of())));

        List<VectorRecord> results = store.search("legal", new float[]{1f, 0f}, 2);

        assertEquals(1, results.size());
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                eq("[1.0,0.0]"), eq("legal"), eq("[1.0,0.0]"), eq(2));
        // 无过滤条件时 LIMIT 即 topK，不放大
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPostFilterMetadataPredicatesAndMinScore() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        record("d1", 0.9, Map.of("lang", "zh")),
                        record("d2", 0.8, Map.of("lang", "en")),
                        record("d3", 0.2, Map.of("lang", "zh"))));

        SearchQuery query = SearchQuery.of("legal", new float[]{1f, 0f}, 2)
                .withFilter(SearchFilter.of(SearchFilter.Predicate.eq("lang", "zh")))
                .withMinScore(0.5);
        List<VectorRecord> results = store.search(query);

        assertEquals(List.of("d1"), results.stream().map(VectorRecord::getId).toList(),
                "d2 谓词不匹配、d3 低于 minScore，均被过滤");
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                eq("[1.0,0.0]"), eq("legal"), eq("[1.0,0.0]"), eq(50));
        // 后过滤时候选放大 max(topK*5, 50)
    }

    @Test
    void shouldDeleteByNamespaceAndId() {
        store.delete("legal", "doc1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("legal"), eq("doc1"));
        assertTrue(sql.getValue().contains("DELETE FROM t_vector_knowledge"));
        assertTrue(sql.getValue().contains("namespace = ? AND id = ?"));
    }

    @Test
    void shouldUpsertAllViaJdbcBatch() {
        store.upsertAll("legal", List.of(
                record("d1", 0, Map.of()).toBuilder().vector(new float[]{1f, 0f}).build(),
                record("d2", 0, Map.of()).toBuilder().vector(new float[]{0f, 1f}).build()));

        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(anyString(), batch.capture());
        assertEquals(2, batch.getValue().size());
        Object[] first = batch.getValue().get(0);
        assertEquals("d1", first[0]);
        assertEquals("legal", first[1]);
        assertEquals("[1.0,0.0]", first[2], "向量转 pgvector 字面量");
    }

    @Test
    void shouldSkipDatabaseCallsForEmptyInputs() {
        store.upsertAll("legal", List.of());
        assertEquals(List.of(), store.search(SearchQuery.of("legal", new float[]{1f}, 0)));
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void shouldNotSupportNativeHybridAndRelyOnAppSideFallback() {
        assertFalse(store.supportsHybrid(), "pgvector 混合走应用侧 EmbeddingRerankPort（P10）");
    }
}
