package org.skylark.langur.domain.harness.execution;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowDefinition;
import org.skylark.langur.domain.harness.workflow.WorkflowStage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A（配置驱动工作流）验收 - {@link DefaultLayerRouter} 感知已注册工作流定义：
 * 凡为 bizCode 注册了定义者即视为 Workflow 信号，无需依赖 bizCode 命名约定；叠加规划特征则升为 Hybrid。
 */
class DefaultLayerRouterWorkflowAwareTest {

    private final DefaultWorkflowRepository repo = new DefaultWorkflowRepository().register(
            WorkflowDefinition.of("contract_review", "合同审查",
                    List.of(WorkflowStage.of("ingest", "读取"))));

    private final DefaultLayerRouter router = new DefaultLayerRouter(repo);

    @Test
    void shouldRouteToWorkflowWhenDefinitionRegistered() {
        // bizCode 不含 workflow/approval/compliance，但已注册定义 → WORKFLOW
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, router.route("contract_review", "请审查这份合同"));
        assertEquals(RuntimeParadigm.WORKFLOW,
                router.paradigmOf(router.route("contract_review", "请审查这份合同")));
    }

    @Test
    void shouldRouteToHybridWhenRegisteredAndPlanLike() {
        assertEquals(RuntimeLayer.HYBRID_LAYER, router.route("contract_review", "请分步规划并审查合同"));
    }

    @Test
    void shouldFallBackToReactForUnregisteredNonMarkerBizCode() {
        assertEquals(RuntimeLayer.REACT_LAYER, router.route("default", "hello"));
    }

    @Test
    void shouldKeepMarkerBasedRoutingWithoutRepository() {
        DefaultLayerRouter bare = new DefaultLayerRouter();
        assertEquals(RuntimeLayer.WORKFLOW_LAYER, bare.route("loan-approval", "x"));
        assertEquals(RuntimeLayer.REACT_LAYER, bare.route("contract_review", "x"),
                "无仓储时不含标记的 bizCode 仍走 ReAct");
    }
}
