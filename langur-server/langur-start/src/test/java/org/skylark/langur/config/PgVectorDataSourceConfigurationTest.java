package org.skylark.langur.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.harness.context.vector.VectorProperties;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.7 验收 - {@link PgVectorDataSourceConfiguration} 离线单测（H2 内存库代打 PG 验证装配链路）：
 * 数据源可达时装配 {@code vectorJdbcTemplate} 且可执行查询；不可达时启动 fail-fast 明确报错（绝不静默、
 * 绝不落回内存实现，P10）；password-ref 缺失 resolver 时 fail-closed。
 */
class PgVectorDataSourceConfigurationTest {

    private final PgVectorDataSourceConfiguration configuration = new PgVectorDataSourceConfiguration();

    @AfterEach
    void tearDown() {
        configuration.destroy();
    }

    private static VectorProperties propsWithUrl(String url) {
        VectorProperties props = new VectorProperties();
        props.setStore("pgvector");
        props.getPgvector().setUrl(url);
        props.getPgvector().setUsername("sa");
        return props;
    }

    private static ObjectProvider<SecretResolver> provider(SecretResolver resolver) {
        return new ObjectProvider<>() {
            @Override
            public SecretResolver getObject() {
                return resolver;
            }

            @Override
            public SecretResolver getObject(Object... args) {
                return resolver;
            }

            @Override
            public SecretResolver getIfAvailable() {
                return resolver;
            }

            @Override
            public SecretResolver getIfUnique() {
                return resolver;
            }
        };
    }

    @Test
    void shouldAssembleWorkingVectorJdbcTemplateWhenDatabaseReachable() {
        VectorProperties props = propsWithUrl("jdbc:h2:mem:pgvector-assembly-test");

        JdbcTemplate template = configuration.vectorJdbcTemplate(props, provider(null));

        assertEquals(1, template.queryForObject("SELECT 1", Integer.class),
                "装配的 vectorJdbcTemplate 可立即执行查询（独立数据源）");
    }

    @Test
    void shouldFailFastWithClearMessageWhenDatabaseUnavailable() {
        VectorProperties props = propsWithUrl("jdbc:postgresql://127.0.0.1:1/langur");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> configuration.vectorJdbcTemplate(props, provider(null)));
        assertTrue(e.getMessage().contains("pgvector datasource unavailable"));
        assertTrue(e.getMessage().contains("127.0.0.1:1"), "报错含目标地址供排查");
    }

    @Test
    void shouldFailClosedWhenPasswordRefWithoutSecretResolver() {
        VectorProperties props = propsWithUrl("jdbc:h2:mem:pgvector-secret-test");
        props.getPgvector().setPasswordRef("kms:cipher");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> configuration.vectorJdbcTemplate(props, provider(null)));
        assertTrue(e.getMessage().contains("fail-closed"));
    }

    @Test
    void shouldFailClosedWhenPasswordRefUnresolvable() {
        VectorProperties props = propsWithUrl("jdbc:h2:mem:pgvector-secret-test2");
        props.getPgvector().setPasswordRef("env:DEFINITELY_ABSENT_VAR_XYZ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> configuration.vectorJdbcTemplate(props,
                        provider(reference -> Optional.empty())));
        assertTrue(e.getMessage().contains("fail-closed"));
    }
}
