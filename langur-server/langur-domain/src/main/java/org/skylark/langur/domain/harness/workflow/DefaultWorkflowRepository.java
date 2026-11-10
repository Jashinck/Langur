package org.skylark.langur.domain.harness.workflow;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link WorkflowRepository} 默认实现（H9）- 内存态注册表，零外部依赖、离线可测（P1）。
 * <p>由 start 层按配置注册工作流定义（P9）；未注册的 bizCode 返回空，交由执行引擎合成兜底工作流。</p>
 */
public class DefaultWorkflowRepository implements WorkflowRepository {

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    public DefaultWorkflowRepository register(WorkflowDefinition definition) {
        if (definition != null && definition.getBizCode() != null) {
            definitions.put(definition.getBizCode(), definition);
        }
        return this;
    }

    @Override
    public Optional<WorkflowDefinition> findByBizCode(String bizCode) {
        if (bizCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(bizCode));
    }
}
