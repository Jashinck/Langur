package org.skylark.langur.config;

import com.zaxxer.hikari.HikariDataSource;
import org.skylark.langur.infrastructure.harness.context.vector.VectorProperties;
import org.skylark.langur.infrastructure.harness.tool.rest.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;

/**
 * pgvector 独立数据源装配（H14.7，补 B4）：{@code langur.vector.store=pgvector} 时按
 * {@code langur.vector.pgvector.*} 构建专用 Hikari 数据源 + {@code vectorJdbcTemplate}（与业务主库分离）。
 * <p>刻意<b>不暴露</b> {@code DataSource} 类型 Bean：避免触发 Boot {@code DataSourceAutoConfiguration}
 * 的 {@code @ConditionalOnMissingBean(DataSource.class)} 退避而顶掉业务数据源；数据源生命周期由本类
 * {@link DisposableBean} 托管。启动即取一次连接做 fail-fast 校验——无库/不可达时明确报错，
 * 绝不静默（P10），也绝不落回内存实现。</p>
 * <p>{@code password-ref} 经 {@link SecretResolver} 解析（缺 KMS fail-closed），明文仅注入连接池，绝不落日志。</p>
 */
@Configuration
@ConditionalOnProperty(name = "langur.vector.store", havingValue = "pgvector")
public class PgVectorDataSourceConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PgVectorDataSourceConfiguration.class);

    private HikariDataSource vectorDataSource;

    @Bean
    public JdbcTemplate vectorJdbcTemplate(VectorProperties properties,
                                           ObjectProvider<SecretResolver> secretResolverProvider) {
        VectorProperties.PgVector pg = properties.getPgvector();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(pg.getUrl());
        dataSource.setUsername(pg.getUsername());
        if (pg.getPasswordRef() != null && !pg.getPasswordRef().isBlank()) {
            SecretResolver resolver = secretResolverProvider.getIfAvailable();
            if (resolver == null) {
                throw new IllegalStateException(
                        "no SecretResolver available for pgvector password-ref (fail-closed)");
            }
            dataSource.setPassword(resolver.resolve(pg.getPasswordRef())
                    .filter(pw -> !pw.isBlank())
                    .orElseThrow(() -> new IllegalStateException(
                            "cannot resolve pgvector password-ref (fail-closed)")));
        }
        dataSource.setPoolName("langur-vector-pool");
        dataSource.setMaximumPoolSize(4);
        // 启动 fail-fast：不可达时抛出明确异常（含底层原因），绝不带病装配
        try (Connection ignored = dataSource.getConnection()) {
            log.info("[Vector] pgvector datasource assembled: url={}, username={}",
                    pg.getUrl(), pg.getUsername());
        } catch (Exception e) {
            dataSource.close();
            throw new IllegalStateException(
                    "pgvector datasource unavailable (langur.vector.pgvector.url=" + pg.getUrl()
                            + "), refuse to start with store=pgvector: " + e.getMessage(), e);
        }
        this.vectorDataSource = dataSource;
        return new JdbcTemplate(dataSource);
    }

    @Override
    public void destroy() {
        if (vectorDataSource != null) {
            vectorDataSource.close();
        }
    }
}
