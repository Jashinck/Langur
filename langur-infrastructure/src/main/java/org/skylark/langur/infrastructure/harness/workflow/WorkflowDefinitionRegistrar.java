package org.skylark.langur.infrastructure.harness.workflow;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流定义注册器（H9+，P9）- 把 {@link WorkflowProperties} 配置映射为领域 {@link WorkflowDefinition}
 * 并填充 {@link DefaultWorkflowRepository}。
 * <p>映射规则：阶段按配置顺序固化；声明 {@code artifactName} 的阶段产出具名产物；
 * {@code requiresApproval} 且带产物者用审批+产物阶段，仅审批者用审批阶段，其余为普通阶段。
 * bizCode 或 stages 为空的定义被跳过（P10 优雅降级，不抛异常阻断启动）。</p>
 */
@Slf4j
public class WorkflowDefinitionRegistrar {

    /** 将配置映射为工作流定义列表（纯函数，离线可测）。 */
    public List<WorkflowDefinition> toDefinitions(WorkflowProperties properties) {
        List<WorkflowDefinition> definitions = new ArrayList<>();
        if (properties == null || properties.getDefinitions() == null) {
            return definitions;
        }
        for (WorkflowProperties.DefinitionProps def : properties.getDefinitions()) {
            if (def == null || def.getBizCode() == null || def.getBizCode().isBlank()
                    || def.getStages() == null || def.getStages().isEmpty()) {
                continue;
            }
            List<WorkflowStage> stages = new ArrayList<>();
            for (WorkflowProperties.StageProps stage : def.getStages()) {
                if (stage == null || stage.getId() == null || stage.getId().isBlank()) {
                    continue;
                }
                stages.add(toStage(stage));
            }
            if (stages.isEmpty()) {
                continue;
            }
            String name = def.getName() != null && !def.getName().isBlank()
                    ? def.getName() : "workflow-" + def.getBizCode();
            definitions.add(WorkflowDefinition.of(def.getBizCode(), name, stages));
        }
        return definitions;
    }

    /** 映射并填充仓储，返回注册条数。 */
    public int populate(DefaultWorkflowRepository repository, WorkflowProperties properties) {
        List<WorkflowDefinition> definitions = toDefinitions(properties);
        definitions.forEach(repository::register);
        if (!definitions.isEmpty()) {
            log.info("[E] registered {} workflow definition(s) from config: {}",
                    definitions.size(),
                    definitions.stream().map(WorkflowDefinition::getBizCode).toList());
        }
        return definitions.size();
    }

    private WorkflowStage toStage(WorkflowProperties.StageProps stage) {
        boolean produces = stage.getArtifactName() != null && !stage.getArtifactName().isBlank();
        if (stage.isRequiresApproval()) {
            return produces
                    ? WorkflowStage.approvalArtifact(stage.getId(), stage.getInstruction(),
                            stage.getArtifactName(), stage.getArtifactType())
                    : WorkflowStage.approval(stage.getId(), stage.getInstruction());
        }
        return produces
                ? WorkflowStage.artifact(stage.getId(), stage.getInstruction(),
                        stage.getArtifactName(), stage.getArtifactType())
                : WorkflowStage.of(stage.getId(), stage.getInstruction());
    }
}
