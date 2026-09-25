package org.skylark.langur.domain.harness.context.vector;

/**
 * 混合检索查询（H14.2）- 不可变、纯 JDK（P1）：词面（{@code queryText}，BM25/稀疏）
 * + 语义（{@code queryVector}，ANN）双通道，由 store 原生下推融合或应用侧兜底融合。
 * <p>融合参数缺省：{@link FusionMode#RRF}（DD17）、rrf-k=60、lexical-weight=0.3（§3.5）。</p>
 */
public final class HybridQuery {

    public static final int DEFAULT_RRF_K = 60;
    public static final double DEFAULT_LEXICAL_WEIGHT = 0.3;

    private final String namespace;
    private final String queryText;
    private final float[] queryVector;
    private final int topK;
    private final SearchFilter filter;
    private final FusionMode fusion;
    private final int rrfK;
    private final double lexicalWeight;

    private HybridQuery(String namespace, String queryText, float[] queryVector, int topK,
                        SearchFilter filter, FusionMode fusion, int rrfK, double lexicalWeight) {
        this.namespace = namespace;
        this.queryText = queryText;
        this.queryVector = queryVector != null ? queryVector.clone() : new float[0];
        this.topK = topK;
        this.filter = filter != null ? filter : SearchFilter.empty();
        this.fusion = fusion != null ? fusion : FusionMode.RRF;
        this.rrfK = rrfK;
        this.lexicalWeight = lexicalWeight;
    }

    public static HybridQuery of(String namespace, String queryText, float[] queryVector, int topK) {
        return new HybridQuery(namespace, queryText, queryVector, topK,
                SearchFilter.empty(), FusionMode.RRF, DEFAULT_RRF_K, DEFAULT_LEXICAL_WEIGHT);
    }

    public HybridQuery withFilter(SearchFilter newFilter) {
        return new HybridQuery(namespace, queryText, queryVector, topK, newFilter, fusion, rrfK, lexicalWeight);
    }

    public HybridQuery withFusion(FusionMode newFusion, int newRrfK, double newLexicalWeight) {
        return new HybridQuery(namespace, queryText, queryVector, topK, filter, newFusion, newRrfK, newLexicalWeight);
    }

    public String getNamespace() {
        return namespace;
    }

    public String getQueryText() {
        return queryText;
    }

    /** 查询向量的防御性副本（不可变约定）。 */
    public float[] getQueryVector() {
        return queryVector.clone();
    }

    public int getTopK() {
        return topK;
    }

    public SearchFilter getFilter() {
        return filter;
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
