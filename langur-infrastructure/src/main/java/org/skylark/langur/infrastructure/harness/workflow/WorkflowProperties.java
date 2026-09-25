package org.skylark.langur.infrastructure.harness.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        /** 是否为 CRITICAL 级审批（J5，P12②）：真则恒人审，决策平面绝不自动放行。 */
        private boolean critical = false;
        /** 产物名（可选）：非空时阶段输出被记录为具名产物。 */
        private String artifactName;
        /** 产物类型（可选，缺省 text）。 */
        private String artifactType;
        /** 阶段决策闸门（J4，可选）：阶段产出后经决策平面判定走向；未配置走默认固定顺序。 */
        private DecisionGateProps decisionGate;
    }

    /**
     * J4 阶段决策闸门配置（P9）：一个判定问题 + 置信阈值 + 分支目标。
     * <p>{@code type=choice}（缺省）按选项值分流 run-next/skip/branch-to-stage/abort/require-approval；
     * 低置信/后端缺失一律回落默认顺序（P10）。</p>
     */
    @Data
    public static class DecisionGateProps {
        /** 判定问题键（答案映射用，必填；留空则闸门不生效）。 */
        private String key;
        /** 判定类型：choice（缺省）| noul。 */
        private String type = "choice";
        /** 判定指令（问题语义，必填；留空则闸门不生效）。 */
        private String instructions;
        /** CHOICE 选项 → 判据说明。 */
        private Map<String, String> criteria = new LinkedHashMap<>();
        /** 置信阈值（留空 → DecisionThresholds.DEFAULT_ROUTING 0.75）。 */
        private Double threshold;
        /** BRANCH 动作的目标阶段 id（branch-to-stage 选项命中时跳转）。 */
        private String branchTo;
    }
}
