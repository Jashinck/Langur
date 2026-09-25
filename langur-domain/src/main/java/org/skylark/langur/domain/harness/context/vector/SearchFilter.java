package org.skylark.langur.domain.harness.context.vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 元数据过滤器（H14.2）- 不可变、纯 JDK（P1），AND 语义。
 * <p>谓词作用于 {@link VectorRecord#getMetadata()}；<b>namespace 隔离不走本过滤器</b>——
 * namespace 是 {@link SearchQuery}/{@link HybridQuery} 的一等字段，由 store 强制过滤，
 * 杜绝跨租户召回（越权红线）。ES/Milvus 实现将谓词翻译为原生 filter；
 * 内存/应用侧实现可直接用 {@link #matches(Map)} 求值。</p>
 */
public final class SearchFilter {

    /** 过滤算子（H14.2）：EQ / IN / GT / LT / GTE / LTE / EXISTS。 */
    public enum Operator {
        EQ, IN, GT, LT, GTE, LTE, EXISTS
    }

    /** 单条谓词：field op value（EXISTS 忽略 value）。 */
    public record Predicate(Operator operator, String field, Object value) {

        public static Predicate eq(String field, Object value) {
            return new Predicate(Operator.EQ, field, value);
        }

        public static Predicate in(String field, Collection<?> values) {
            return new Predicate(Operator.IN, field, values);
        }

        public static Predicate gt(String field, Number value) {
            return new Predicate(Operator.GT, field, value);
        }

        public static Predicate lt(String field, Number value) {
            return new Predicate(Operator.LT, field, value);
        }

        public static Predicate gte(String field, Number value) {
            return new Predicate(Operator.GTE, field, value);
        }

        public static Predicate lte(String field, Number value) {
            return new Predicate(Operator.LTE, field, value);
        }

        public static Predicate exists(String field) {
            return new Predicate(Operator.EXISTS, field, null);
        }

        /** 对一条记录的 metadata 求值（纯 JDK，供内存/应用侧实现复用）。 */
        public boolean test(Map<String, Object> metadata) {
            Object actual = metadata != null ? metadata.get(field) : null;
            return switch (operator) {
                case EXISTS -> actual != null;
                case EQ -> valueEquals(actual, value);
                case IN -> value instanceof Collection<?> candidates
                        && candidates.stream().anyMatch(candidate -> valueEquals(actual, candidate));
                case GT, LT, GTE, LTE -> compare(actual, value, operator);
            };
        }

        private static boolean valueEquals(Object actual, Object expected) {
            if (actual instanceof Number a && expected instanceof Number b) {
                return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
            }
            return Objects.equals(actual, expected);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static boolean compare(Object actual, Object expected, Operator op) {
            if (!(actual instanceof Comparable) || !(expected instanceof Comparable)) {
                return false;
            }
            int cmp;
            if (actual instanceof Number a && expected instanceof Number b) {
                cmp = Double.compare(a.doubleValue(), b.doubleValue());
            } else if (actual.getClass() == expected.getClass()) {
                cmp = ((Comparable) actual).compareTo(expected);
            } else {
                return false;
            }
            return switch (op) {
                case GT -> cmp > 0;
                case LT -> cmp < 0;
                case GTE -> cmp >= 0;
                case LTE -> cmp <= 0;
                default -> false;
            };
        }
    }

    private static final SearchFilter EMPTY = new SearchFilter(List.of());

    private final List<Predicate> predicates;

    private SearchFilter(List<Predicate> predicates) {
        this.predicates = List.copyOf(predicates);
    }

    /** 空过滤器：匹配一切（不影响召回）。 */
    public static SearchFilter empty() {
        return EMPTY;
    }

    public static SearchFilter of(Predicate... predicates) {
        return predicates == null || predicates.length == 0 ? EMPTY : new SearchFilter(List.of(predicates));
    }

    /** 追加一条谓词（AND），返回新实例（不可变）。 */
    public SearchFilter and(Predicate predicate) {
        List<Predicate> combined = new ArrayList<>(predicates);
        combined.add(predicate);
        return new SearchFilter(combined);
    }

    public List<Predicate> getPredicates() {
        return predicates;
    }

    public boolean isEmpty() {
        return predicates.isEmpty();
    }

    /** 全部谓词 AND 求值；空过滤器恒真。 */
    public boolean matches(Map<String, Object> metadata) {
        return predicates.stream().allMatch(predicate -> predicate.test(metadata));
    }
}
