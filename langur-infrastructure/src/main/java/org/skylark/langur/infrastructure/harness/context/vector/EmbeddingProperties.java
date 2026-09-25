package org.skylark.langur.infrastructure.harness.context.vector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * C 组件 L4 - 语义嵌入配置（H2，M2）。
 * <p>{@code type=lexical}（默认，本地词袋，无外部依赖）或 {@code type=llm}（走 provider /embeddings 真实语义向量）。
 * {@code dimensions} 配置化解耦向量库维度（DD7），须与 {@code VectorStore} 建表维度一致。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "langur.embedding")
public class EmbeddingProperties {

    /** lexical（默认）| llm。 */
    private String type = "lexical";

    /** 语义向量维度（DD7 配置化，替换硬编码）；仅 type=llm 生效。 */
    private int dimensions = 1536;

    /** 批量向量化批大小。 */
    private int batchSize = 16;

    /** 覆盖 EMBEDDING 角色模型；留空则用 {@code langur.llm.role-models.EMBEDDING}。 */
    private String model;

    /** 覆盖 provider base-url；留空则用默认 provider。 */
    private String baseUrl;

    /** 覆盖 api-key；留空则用默认 provider。 */
    private String apiKey;

    /** OpenAI 兼容 embeddings 路径。 */
    private String path = "/embeddings";

    /** 单次调用超时秒数。 */
    private int timeoutSeconds = 15;
}
