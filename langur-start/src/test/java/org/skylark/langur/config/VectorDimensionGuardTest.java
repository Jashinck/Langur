package org.skylark.langur.config;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.infrastructure.harness.context.vector.VectorProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14.8 验收 - 维度一致性守卫（DD19 全局单维）：未配置由 embedding 驱动；一致通过；
 * 不一致 fail-fast 明确报错（消息含两侧维度）。离线确定性：纯 stub，无容器。
 */
class VectorDimensionGuardTest {

    /** 固定维度嵌入端口 stub。 */
    static class FixedEmbedding implements EmbeddingPort {
        private final int dims;

        FixedEmbedding(int dims) {
            this.dims = dims;
        }

        @Override
        public float[] embed(String text) {
            return new float[dims];
        }

        @Override
        public int dimensions() {
            return dims;
        }
    }

    @Test
    void shouldDeriveFromEmbeddingWhenDimensionUnset() {
        assertEquals(256, VectorDimensionGuard.resolveEffectiveDimension(0, 256), "0=未配置，取 embedding 维度");
        assertEquals(1536, VectorDimensionGuard.resolveEffectiveDimension(-1, 1536), "负值同样视为未配置");
    }

    @Test
    void shouldPassWhenConfiguredMatchesEmbedding() {
        assertEquals(256, VectorDimensionGuard.resolveEffectiveDimension(256, 256));
        assertEquals(1536, VectorDimensionGuard.resolveEffectiveDimension(1536, 1536));
    }

    @Test
    void shouldFailFastOnDimensionMismatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> VectorDimensionGuard.resolveEffectiveDimension(1536, 256));
        assertTrue(ex.getMessage().contains("1536"), "报错须含配置维度");
        assertTrue(ex.getMessage().contains("256"), "报错须含嵌入维度");
    }

    @Test
    void shouldValidateOnPostConstructPathMatch() {
        VectorProperties props = new VectorProperties();
        props.setDimension(256);
        VectorDimensionGuard guard = new VectorDimensionGuard(props, new FixedEmbedding(256));

        guard.validate();
    }

    @Test
    void shouldThrowOnPostConstructPathMismatch() {
        VectorProperties props = new VectorProperties();
        props.setDimension(1536);
        VectorDimensionGuard guard = new VectorDimensionGuard(props, new FixedEmbedding(256));

        assertThrows(IllegalStateException.class, guard::validate, "启动期维度不一致必须 fail-fast");
    }

    @Test
    void shouldPassWhenDimensionUnsetRegardlessOfEmbedding() {
        VectorProperties props = new VectorProperties();
        // dimension 缺省 0
        VectorDimensionGuard guard = new VectorDimensionGuard(props, new FixedEmbedding(256));

        guard.validate();
    }
}
