package org.skylark.langur.infrastructure.harness.workflow;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A（配置驱动工作流）验收 - {@link WorkflowDefinitionRegistrar} 把 {@link WorkflowProperties}
 * 映射为领域工作流定义：阶段类型（普通/审批/产物/审批+产物）正确、无效定义被跳过（P10）、填充仓储可被路由查找。
 */
class WorkflowDefinitionRegistrarTest {

    private final WorkflowDefinitionRegistrar registrar = new WorkflowDefinitionRegistrar();

    @Test
    void shouldMapStagesByType() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.DefinitionProps def = new WorkflowProperties.DefinitionProps();
        def.setBizCode("contract_review");
        def.setName("合同审查");
        def.setStages(List.of(
                stage("ingest", "读取合同与尽调", false, null, null),
                stage("review", "出具审查结论", false, "review-report", "report"),
                stage("special", "特批项审批", true, "approval-items", "approval-items")));
        props.setDefinitions(List.of(def));

        List<WorkflowDefinition> definitions = registrar.toDefinitions(props);

        assertEquals(1, definitions.size());
        WorkflowDefinition mapped = definitions.get(0);
        assertEquals("contract_review", mapped.getBizCode());
        assertEquals("合同审查", mapped.getName());
        List<WorkflowStage> stages = mapped.getStages();
        assertEquals(3, stages.size());

        assertFalse(stages.get(0).isRequiresApproval());
        assertFalse(stages.get(0).producesArtifact());

        assertFalse(stages.get(1).isRequiresApproval());
        assertTrue(stages.get(1).producesArtifact());
        assertEquals("review-report", stages.get(1).getArtifactName());
        assertEquals("report", stages.get(1).getArtifactType());

        assertTrue(stages.get(2).isRequiresApproval());
        assertTrue(stages.get(2).producesArtifact());
        assertEquals("approval-items", stages.get(2).getArtifactName());
    }

    @Test
    void shouldSkipInvalidDefinitionsAndStages() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.DefinitionProps blankBiz = new WorkflowProperties.DefinitionProps();
        blankBiz.setBizCode("  ");
        blankBiz.setStages(List.of(stage("s", "x", false, null, null)));
        WorkflowProperties.DefinitionProps noStages = new WorkflowProperties.DefinitionProps();
        noStages.setBizCode("no-stages");
        WorkflowProperties.DefinitionProps good = new WorkflowProperties.DefinitionProps();
        good.setBizCode("good");
        good.setStages(List.of(stage(null, "skip-me", false, null, null),
                stage("keep", "保留", false, null, null)));
        props.setDefinitions(List.of(blankBiz, noStages, good));

        List<WorkflowDefinition> definitions = registrar.toDefinitions(props);

        assertEquals(1, definitions.size(), "空 bizCode / 无阶段的定义被跳过");
        assertEquals("good", definitions.get(0).getBizCode());
        assertEquals(1, definitions.get(0).getStages().size(), "无 id 的阶段被跳过");
        assertEquals("keep", definitions.get(0).getStages().get(0).getId());
    }

    @Test
    void shouldDefaultNameAndPopulateRepository() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.DefinitionProps def = new WorkflowProperties.DefinitionProps();
        def.setBizCode("loan_flow");
        def.setStages(List.of(stage("s1", "x", false, null, null)));
        props.setDefinitions(List.of(def));

        DefaultWorkflowRepository repo = new DefaultWorkflowRepository();
        int count = registrar.populate(repo, props);

        assertEquals(1, count);
        WorkflowDefinition found = repo.findByBizCode("loan_flow").orElseThrow();
        assertEquals("workflow-loan_flow", found.getName(), "未配置 name 时用默认名");
    }

    @Test
    void shouldReturnEmptyForNullProperties() {
        assertTrue(registrar.toDefinitions(null).isEmpty());
        assertEquals(0, registrar.populate(new DefaultWorkflowRepository(), new WorkflowProperties()));
    }

    private WorkflowProperties.StageProps stage(String id, String instruction, boolean approval,
                                                String artifactName, String artifactType) {
        WorkflowProperties.StageProps stage = new WorkflowProperties.StageProps();
        stage.setId(id);
        stage.setInstruction(instruction);
        stage.setRequiresApproval(approval);
        stage.setArtifactName(artifactName);
        stage.setArtifactType(artifactType);
        return stage;
    }
}
