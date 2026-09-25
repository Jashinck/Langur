package org.skylark.langur.domain.harness.security;

import java.util.Optional;

/**
 * H10 高危审批 - 审批单存储端口（S 组件协作）。
 * <p>领域层定义契约，基础设施层提供实现（默认内存态，可替换为持久化/分布式存储）。
 * 支撑"挂起任务 → 审批回调 → 从快照恢复"链路。</p>
 */
public interface ApprovalPort {

    /** 暂存审批请求单。 */
    void save(ApprovalRequest request);

    /** 按请求单 ID 查询。 */
    Optional<ApprovalRequest> findById(String requestId);

    /**
     * 查询指定 trace + tool 最新的待审批/已决请求单。
     * <p>校验链据此判断是否已存在审批单，避免重复创建。</p>
     */
    Optional<ApprovalRequest> findLatest(String traceId, String toolId);
}
