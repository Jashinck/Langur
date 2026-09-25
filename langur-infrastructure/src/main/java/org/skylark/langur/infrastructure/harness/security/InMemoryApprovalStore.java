package org.skylark.langur.infrastructure.harness.security;

import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.security.ApprovalRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H10 高危审批 - 审批单内存态存储（默认实现）。
 * <p>可替换为持久化/分布式实现（DB / Redis）；此处以线程安全 Map 承载挂起-回调-恢复链路。</p>
 */
@Component
public class InMemoryApprovalStore implements ApprovalPort {

    private final Map<String, ApprovalRequest> store = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRequest request) {
        if (request != null && request.getRequestId() != null) {
            store.put(request.getRequestId(), request);
        }
    }

    @Override
    public Optional<ApprovalRequest> findById(String requestId) {
        return Optional.ofNullable(requestId).map(store::get);
    }

    @Override
    public Optional<ApprovalRequest> findLatest(String traceId, String toolId) {
        return store.values().stream()
                .filter(r -> eq(r.getTraceId(), traceId) && eq(r.getToolId(), toolId))
                .max(Comparator.comparing(ApprovalRequest::getCreatedAt));
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
