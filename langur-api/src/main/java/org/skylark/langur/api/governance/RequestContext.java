package org.skylark.langur.api.governance;

/**
 * 请求治理上下文（§13.3）- 承载经治理链解析后的可信 traceId / userId / tenantId。
 * <p>ThreadLocal 存储，控制器可据此以可信身份覆盖业务入参；请求结束必须 {@link #clear()}。</p>
 */
public final class RequestContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public record Holder(String traceId, String userId, String tenantId) {
    }

    public static void set(String traceId, String userId, String tenantId) {
        HOLDER.set(new Holder(traceId, userId, tenantId));
    }

    public static Holder get() {
        return HOLDER.get();
    }

    public static String traceId() {
        Holder h = HOLDER.get();
        return h != null ? h.traceId() : null;
    }

    public static String userId() {
        Holder h = HOLDER.get();
        return h != null ? h.userId() : null;
    }

    public static String tenantId() {
        Holder h = HOLDER.get();
        return h != null ? h.tenantId() : null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
