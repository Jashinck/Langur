package org.skylark.langur.domain.harness.workflow;

import java.util.Optional;

/**
 * 工作流定义仓储端口（H9）- 按 bizCode 解析顶层工作流定义。
 * <p>领域层定义契约，实现可来自配置/DB（P9 配置驱动）。缺失定义时 {@link
 * org.skylark.langur.domain.harness.execution.WorkflowExecutionLoop} 会按 bizCode 合成兜底工作流。</p>
 */
public interface WorkflowRepository {

    Optional<WorkflowDefinition> findByBizCode(String bizCode);
}
