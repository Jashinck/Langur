package org.skylark.langur.api.governance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API 治理链配置（§13.3）- {@code langur.governance}。
 * <p>默认关闭以兼容本地/测试；生产开启后按 鉴权 → 租户 → 限流 → 幂等 → TraceId 顺序治理。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.governance")
public class GovernanceProperties {

    private boolean enabled = false;
    /** 仅治理该前缀下的请求。 */
    private String protectedPrefix = "/api/";
    private String traceHeader = "X-Trace-Id";
    private String tenantHeader = "X-Tenant-Id";
    private String userHeader = "X-User-Id";
    private String idempotencyHeader = "Idempotency-Key";
    private boolean idempotencyEnabled = true;

    private Auth auth = new Auth();
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class Auth {
        private boolean enabled = true;
        private List<AuthToken> tokens = new ArrayList<>();
    }

    /** 令牌 → 租户/用户绑定，用于鉴权与租户越权校验。 */
    @Data
    public static class AuthToken {
        private String token;
        private String tenant;
        private String user;
    }

    @Data
    public static class RateLimit {
        /** 每 key 每分钟许可数。 */
        private int permitsPerMinute = 600;
    }
}
