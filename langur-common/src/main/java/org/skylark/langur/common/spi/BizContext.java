package org.skylark.langur.common.spi;

import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * SPI 业务上下文 - 贯穿七大 SPI 扩展点的只读入参 + 可变属性袋。
 * <p>common 层零依赖（仅 JDK + lombok），供各业务域 SPI 实现共享。</p>
 */
@Getter
@Builder
public class BizContext {

    /** 业务域标识，SPI 路由主键（§11.2）。 */
    private final String bizCode;
    private final String userId;
    private final String tenantId;
    private final String sessionId;
    private final String agentId;
    private final String userMessage;

    /** 上下文数据袋：ContextEnricherSPI 写入，其余 SPI 读取。 */
    @Builder.Default
    private final Map<String, Object> attributes = new HashMap<>();

    public Object attribute(String key) {
        return attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    public BizContext putAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    public static BizContext of(String bizCode) {
        return BizContext.builder().bizCode(bizCode).build();
    }
}
