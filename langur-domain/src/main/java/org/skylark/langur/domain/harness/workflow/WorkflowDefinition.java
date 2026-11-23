package org.skylark.langur.domain.harness.workflow;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 顶层工作流定义（H9，§5.3）- 绑定 bizCode 的确定性阶段序列，由 Harness 全权驱动。
 * <p>强合规/审批类业务经此锁定执行边界：阶段顺序、审批闸门均由定义固化，LLM 不参与控制流决策。</p>
 */
@Getter
@Builder
public class WorkflowDefinition {

    private final String bizCode;
    private final String name;
    private final List<WorkflowStage> stages;

    public static WorkflowDefinition of(String bizCode, String name, List<WorkflowStage> stages) {
        return WorkflowDefinition.builder().bizCode(bizCode).name(name).stages(stages).build();
    }

    /** 单阶段直通工作流：无审批，直接委派下层执行整条指令。 */
    public static WorkflowDefinition passthrough(String bizCode, String instruction) {
        return of(bizCode, "passthrough-" + bizCode,
                List.of(WorkflowStage.of("main", instruction)));
    }
}
