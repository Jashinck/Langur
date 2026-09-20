package org.skylark.langur.domain.harness.lifecycle;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Hook 执行上下文 - 贯穿单次拦截的载荷与属性
 */
@Getter
public class HookContext {

    private final HookPoint point;
    private final String traceId;
    private final Map<String, Object> attributes;
    private String payload;

    private HookContext(HookPoint point, String traceId, String payload) {
        this.point = point;
        this.traceId = traceId;
        this.payload = payload;
        this.attributes = new HashMap<>();
    }

    public static HookContext of(HookPoint point, String traceId, String payload) {
        return new HookContext(point, traceId, payload);
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }
}
