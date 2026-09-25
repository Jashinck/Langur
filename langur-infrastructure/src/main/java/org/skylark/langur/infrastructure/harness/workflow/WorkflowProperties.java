package org.skylark.langur.infrastructure.harness.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流定义配置（H9+，P9）- {@code langur.workflow}。
 * <p>以配置驱动方式向 {@code DefaultWorkflowRepository} 注册确定性阶段序列，使 WORKFLOW/HYBRID
 * 多阶段编排在生产可运行（此前仓储默认空，仅走兜底单阶段）。凡在此注册了定义的 bizCode，
 * {@code DefaultLayerRouter} 一律视为 Workflow 信号并路由到顶层/混合范式。</p>
 * <p>阶段可声明 {@code artifactName/artifactType} 产出具名产物（多产物输出，如审查报告 + 特批项报告），
 * {@code requiresApproval=true} 则为审批闸门阶段（经 ApprovalPort 挂起-恢复）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.workflow")
public class WorkflowProperties {

    private List<DefinitionProps> definitions = new ArrayList<>();

    @Data
    public static class DefinitionProps {
        /** 业务域编码，路由与仓储查找的键。 */
        private String bizCode;
        /** 工作流名称（可观测/进度回报展示）。 */
        private String name;
        private List<StageProps> stages = new ArrayList<>();
    }

    @Data
    public static class StageProps {
        /** 阶段标识，快照/审批/进度回报以此定位。 */
        private String id;
        /** 阶段指令，委派下层执行器时框定为子目标。 */
        private String instruction;
        /** 是否为审批闸门阶段。 */
        private boolean requiresApproval = false;
        /** 产物名（可选）：非空时阶段输出被记录为具名产物。 */
        private String artifactName;
        /** 产物类型（可选，缺省 text）。 */
        private String artifactType;
    }
}
