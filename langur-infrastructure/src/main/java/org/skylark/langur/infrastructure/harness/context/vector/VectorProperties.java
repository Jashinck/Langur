package org.skylark.langur.infrastructure.harness.context.vector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * C 组件 L4 - 向量库统一配置（H14.4，{@code langur.vector.*}，补 B5，P9）。
 * <p>{@code store} 选择实现（memory|pgvector|elasticsearch|milvus，默认 memory 行为不变）；
 * {@code dimension} 为全局单维（DD19），须与 {@link org.skylark.langur.domain.harness.context.vector.EmbeddingPort#dimensions()}
 * 一致（启动守卫见 H14.8；{@code <=0} 表示未显式配置，由 embedding 维度驱动，不触发 fail-fast）。
 * {@code hybrid.*} 控制原生混合下推；ES/Milvus/pgvector 子块承载各自连接与凭证（{@code password-ref} 经
 * {@code SecretResolver}，绝不落明文，缺 KMS fail-closed）。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "langur.vector")
public class VectorProperties {

    /** 存储实现：memory（默认）| pgvector | elasticsearch | milvus。 */
    private String store = "memory";

    /** 全局向量维度（DD19）；{@code <=0} 表示由 {@code EmbeddingPort.dimensions()} 驱动（不 fail-fast）。 */
    private int dimension = 0;

    private final Hybrid hybrid = new Hybrid();
    private final Elasticsearch elasticsearch = new Elasticsearch();
    private final Milvus milvus = new Milvus();
    private final PgVector pgvector = new PgVector();

    /** 混合检索下推配置（H14.3/H14.4）。 */
    @Getter
    @Setter
    public static class Hybrid {
        /** 是否启用混合检索（默认关闭，保持 memory/pgvector 既有路径不变）。 */
        private boolean enabled = false;
        /** native（服务端下推）| app（强制走 {@code EmbeddingRerankPort} 应用侧）。 */
        private String mode = "native";
        /** 融合算法：rrf（默认，DD17）| weighted。 */
        private String fusion = "rrf";
        /** RRF 常数 k（默认 60）。 */
        private int rrfK = 60;
        /** weighted 模式下词面权重（默认 0.3）。 */
        private double lexicalWeight = 0.3d;
    }

    /** Elasticsearch 连接（DD15 官方客户端；H14.5）。 */
    @Getter
    @Setter
    public static class Elasticsearch {
        private String uris = "http://localhost:9200";
        private String index = "langur_vectors";
        private String username = "";
        /** 密钥引用（env:/prop:/kms:），经 {@code SecretResolver} 解析，绝不落明文。 */
        private String passwordRef;
        /** RRF retriever 授权层（DD20）；未授权时降级为两查询 + 客户端 RRF 融合。 */
        private boolean nativeRrf = true;
    }

    /** Milvus 连接（DD16 官方 SDK；H14.6）。 */
    @Getter
    @Setter
    public static class Milvus {
        private String uri = "http://localhost:19530";
        private String collection = "langur_vectors";
        private String username = "";
        /** 密钥引用（env:/prop:/kms:），经 {@code SecretResolver} 解析，绝不落明文。 */
        private String passwordRef;
    }

    /** pgvector 独立数据源（补 B4；H14.7）。 */
    @Getter
    @Setter
    public static class PgVector {
        private String url = "jdbc:postgresql://localhost:5432/langur";
        private String username = "postgres";
        /** 密钥引用（env:/prop:/kms:），经 {@code SecretResolver} 解析，绝不落明文。 */
        private String passwordRef;
    }
}
