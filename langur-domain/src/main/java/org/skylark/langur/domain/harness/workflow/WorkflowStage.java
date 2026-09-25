package org.skylark.langur.domain.harness.workflow;

import lombok.Builder;
import lombok.Getter;

/**
 * 顶层工作流阶段（H9，§5.3）- Workflow 引擎的一个确定性编排单元。
 * <p>与 ReAct 由 LLM 决定下一步不同，Workflow 阶段的顺序由 Harness 全权锁定：引擎按序驱动，
 * 每个阶段委派下层执行器完成实际动作。{@link #isRequiresApproval()} 为真时，阶段执行前须经人工审批
 * （强合规/审批边界），未批准则挂起任务，批准后从快照恢复。</p>
 */
@Getter
@Builder
public class WorkflowStage {

    /** 阶段标识，快照/审批/进度回报以此定位。 */
    private final String id;

    /** 阶段指令（委派下层执行器时框定为子目标）。 */
    private final String instruction;

    /** 是否为审批闸门阶段：执行前须获得人工批准。 */
    private final boolean requiresApproval;

    /** 产物名（可选）：非空时阶段输出被记录为该具名产物，支撑多产物交付。 */
    private final String artifactName;

    /** 产物类型（可选，缺省 text）：如 report / approval-items。 */
    private final String artifactType;

    public static WorkflowStage of(String id, String instruction) {
        return WorkflowStage.builder().id(id).instruction(instruction).requiresApproval(false).build();
    }

    public static WorkflowStage approval(String id, String instruction) {
        return WorkflowStage.builder().id(id).instruction(instruction).requiresApproval(true).build();
    }

    /** 产物阶段：执行输出被记录为具名产物（多产物输出）。 */
    public static WorkflowStage artifact(String id, String instruction, String artifactName, String artifactType) {
        return WorkflowStage.builder().id(id).instruction(instruction).requiresApproval(false)
                .artifactName(artifactName).artifactType(artifactType).build();
    }

    /** 审批 + 产物阶段：先经审批闸门，批准后输出被记录为具名产物。 */
    public static WorkflowStage approvalArtifact(String id, String instruction,
                                                 String artifactName, String artifactType) {
        return WorkflowStage.builder().id(id).instruction(instruction).requiresApproval(true)
                .artifactName(artifactName).artifactType(artifactType).build();
    }

    /** 该阶段是否声明了具名产物。 */
    public boolean producesArtifact() {
        return artifactName != null && !artifactName.isBlank();
    }
}
