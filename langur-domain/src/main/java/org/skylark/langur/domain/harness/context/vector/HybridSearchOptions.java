package org.skylark.langur.domain.harness.context.vector;

/**
 * 混合检索策略选项（H14.3）- 不可变、纯 JDK（P1）。由 start 层从 {@code langur.vector.hybrid.*} 映射注入，
 * 领域层据此在 {@link VectorMemoryService#recall} 决定走原生下推还是应用侧兜底。
 * <p>红线：缺省 {@link #disabled()}（enabled=false）——memory/pgvector 既有召回路径完全不变（P10）。
 * 仅当 {@code enabled && mode=NATIVE && store.supportsHybrid()} 时才走 {@link VectorStore#hybridSearch}；
 * 原生异常由 {@link VectorMemoryService} 捕获回退应用侧，绝不中断主链路。</p>
 */
public final class HybridSearchOptions {

    /** 混合检索模式：{@code NATIVE} 服务端下推；{@code APP} 强制应用侧 {@link RerankPort} 兜底。 */
    public enum Mode {
        NATIVE, APP
    }

    private static final HybridSearchOptions DISABLED =
            new HybridSearchOptions(false, Mode.NATIVE, FusionMode.RRF,
                    HybridQuery.DEFAULT_RRF_K, HybridQuery.DEFAULT_LEXICAL_WEIGHT);

    private final boolean enabled;
    private final Mode mode;
    private final FusionMode fusion;
    private final int rrfK;
    private final double lexicalWeight;

    private HybridSearchOptions(boolean enabled, Mode mode, FusionMode fusion, int rrfK, double lexicalWeight) {
        this.enabled = enabled;
        this.mode = mode != null ? mode : Mode.NATIVE;
        this.fusion = fusion != null ? fusion : FusionMode.RRF;
        this.rrfK = rrfK;
        this.lexicalWeight = lexicalWeight;
    }

    /** 缺省：混合关闭，走既有 search + 应用侧 rerank 路径。 */
    public static HybridSearchOptions disabled() {
        return DISABLED;
    }

    public static HybridSearchOptions of(boolean enabled, Mode mode, FusionMode fusion, int rrfK, double lexicalWeight) {
        return new HybridSearchOptions(enabled, mode, fusion, rrfK, lexicalWeight);
    }

    /** 是否应对给定 store 走原生混合下推（能力位 + 配置双门）。 */
    public boolean shouldUseNativeHybrid(VectorStore store) {
        return enabled && mode == Mode.NATIVE && store != null && store.supportsHybrid();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public FusionMode getFusion() {
        return fusion;
    }

    public int getRrfK() {
        return rrfK;
    }

    public double getLexicalWeight() {
        return lexicalWeight;
    }
}
