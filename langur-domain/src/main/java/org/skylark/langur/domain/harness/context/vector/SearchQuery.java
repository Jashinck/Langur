package org.skylark.langur.domain.harness.context.vector;

/**
 * 结构化检索查询（H14.2）- 不可变、纯 JDK（P1）。
 * <p>{@code namespace} 为强制隔离字段（越权红线）；{@code filter} 缺省空（匹配一切）；
 * {@code minScore} 缺省 0（不设阈值，cosine 分数可为负）。经 {@code withXxx} 派生新实例。</p>
 */
public final class SearchQuery {

    private final String namespace;
    private final float[] queryVector;
    private final int topK;
    private final SearchFilter filter;
    private final double minScore;

    private SearchQuery(String namespace, float[] queryVector, int topK,
                        SearchFilter filter, double minScore) {
        this.namespace = namespace;
        this.queryVector = queryVector != null ? queryVector.clone() : new float[0];
        this.topK = topK;
        this.filter = filter != null ? filter : SearchFilter.empty();
        this.minScore = minScore;
    }

    public static SearchQuery of(String namespace, float[] queryVector, int topK) {
        return new SearchQuery(namespace, queryVector, topK, SearchFilter.empty(), 0.0);
    }

    public SearchQuery withFilter(SearchFilter newFilter) {
        return new SearchQuery(namespace, queryVector, topK, newFilter, minScore);
    }

    public SearchQuery withMinScore(double newMinScore) {
        return new SearchQuery(namespace, queryVector, topK, filter, newMinScore);
    }

    public SearchQuery withTopK(int newTopK) {
        return new SearchQuery(namespace, queryVector, newTopK, filter, minScore);
    }

    public String getNamespace() {
        return namespace;
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

    public double getMinScore() {
        return minScore;
    }
}
