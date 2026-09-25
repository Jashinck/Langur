package org.skylark.langur.infrastructure.harness.context.vector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.4 验收 - {@link VectorProperties} 配置绑定（{@code langur.vector.*}）：
 * 缺省 {@code store=memory}、混合关闭（既有路径不变，P10）；显式配置正确绑定含 ES/Milvus/pgvector 子块与
 * {@code password-ref}（密钥引用，绝不落明文）。离线确定性：{@link Binder} + Map 属性源，无需容器。
 */
class VectorPropertiesBindingTest {

    @Test
    void shouldBindSafeDefaultsWhenUnset() {
        VectorProperties props = new VectorProperties();

        assertEquals("memory", props.getStore(), "缺省 store=memory 行为不变");
        assertEquals(0, props.getDimension(), "缺省维度 0 = 由 EmbeddingPort 驱动，不 fail-fast");
        assertFalse(props.getHybrid().isEnabled(), "缺省混合关闭，memory/pgvector 路径不变");
        assertEquals("native", props.getHybrid().getMode());
        assertEquals("rrf", props.getHybrid().getFusion());
        assertEquals(60, props.getHybrid().getRrfK());
        assertEquals(0.3d, props.getHybrid().getLexicalWeight());
        assertTrue(props.getElasticsearch().isNativeRrf(), "DD20 缺省假定授权可用");
    }

    @Test
    void shouldBindHybridAndElasticsearchFromPropertySource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("langur.vector.store", "elasticsearch");
        source.put("langur.vector.dimension", "1536");
        source.put("langur.vector.hybrid.enabled", "true");
        source.put("langur.vector.hybrid.mode", "app");
        source.put("langur.vector.hybrid.fusion", "weighted");
        source.put("langur.vector.hybrid.rrf-k", "30");
        source.put("langur.vector.hybrid.lexical-weight", "0.5");
        source.put("langur.vector.elasticsearch.uris", "http://es:9200");
        source.put("langur.vector.elasticsearch.index", "kb");
        source.put("langur.vector.elasticsearch.username", "elastic");
        source.put("langur.vector.elasticsearch.password-ref", "env:ES_PW");
        source.put("langur.vector.elasticsearch.native-rrf", "false");

        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        VectorProperties props = binder.bind("langur.vector", VectorProperties.class).get();

        assertEquals("elasticsearch", props.getStore());
        assertEquals(1536, props.getDimension());
        assertTrue(props.getHybrid().isEnabled());
        assertEquals("app", props.getHybrid().getMode());
        assertEquals("weighted", props.getHybrid().getFusion());
        assertEquals(30, props.getHybrid().getRrfK());
        assertEquals(0.5d, props.getHybrid().getLexicalWeight());
        assertEquals("http://es:9200", props.getElasticsearch().getUris());
        assertEquals("kb", props.getElasticsearch().getIndex());
        assertEquals("elastic", props.getElasticsearch().getUsername());
        assertEquals("env:ES_PW", props.getElasticsearch().getPasswordRef(), "password-ref 绑定为引用而非明文");
        assertFalse(props.getElasticsearch().isNativeRrf(), "DD20 未授权可关闭原生 RRF 走客户端融合");
    }

    @Test
    void shouldBindMilvusAndPgVectorSubBlocks() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("langur.vector.milvus.uri", "http://milvus:19530");
        source.put("langur.vector.milvus.collection", "vecs");
        source.put("langur.vector.milvus.password-ref", "kms:cipher");
        source.put("langur.vector.pgvector.url", "jdbc:postgresql://db:5432/kb");
        source.put("langur.vector.pgvector.username", "kb");
        source.put("langur.vector.pgvector.password-ref", "prop:pg.pw");

        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        VectorProperties props = binder.bind("langur.vector", VectorProperties.class).get();

        assertEquals("http://milvus:19530", props.getMilvus().getUri());
        assertEquals("vecs", props.getMilvus().getCollection());
        assertEquals("kms:cipher", props.getMilvus().getPasswordRef());
        assertEquals("jdbc:postgresql://db:5432/kb", props.getPgvector().getUrl());
        assertEquals("kb", props.getPgvector().getUsername());
        assertEquals("prop:pg.pw", props.getPgvector().getPasswordRef());
    }
}
