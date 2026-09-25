package org.skylark.langur.config;

import org.skylark.langur.application.stream.TaskProgressBus;
import org.skylark.langur.common.cache.DistributedLock;
import org.skylark.langur.domain.harness.context.AgentContextRepository;
import org.skylark.langur.domain.harness.context.ContextAssembler;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.domain.harness.context.vector.FusionMode;
import org.skylark.langur.domain.harness.context.vector.HybridSearchOptions;
import org.skylark.langur.domain.harness.context.vector.RerankPort;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.execution.ExecutionLoopService;
import org.skylark.langur.domain.harness.execution.ExecutionProgressPort;
import org.skylark.langur.domain.harness.execution.HeuristicPlanner;
import org.skylark.langur.domain.harness.execution.LoopDetector;
import org.skylark.langur.domain.harness.execution.ParadigmDispatchingExecutionLoop;
import org.skylark.langur.domain.harness.execution.PlanAndExecuteExecutionLoop;
import org.skylark.langur.domain.harness.execution.Planner;
import org.skylark.langur.domain.harness.execution.ReActExecutionLoop;
import org.skylark.langur.domain.harness.execution.RetryPolicy;
import org.skylark.langur.domain.harness.execution.RuntimeParadigm;
import org.skylark.langur.domain.harness.execution.WorkflowExecutionLoop;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.security.ApprovalPort;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.harness.workflow.DefaultWorkflowRepository;
import org.skylark.langur.domain.harness.workflow.WorkflowRepository;
import org.skylark.langur.domain.port.DefaultFallbackStrategy;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.infrastructure.cache.CacheProperties;
import org.skylark.langur.infrastructure.harness.context.vector.VectorProperties;
import org.skylark.langur.infrastructure.harness.execution.LlmPlanner;
import org.skylark.langur.infrastructure.harness.execution.LockingExecutionLoopService;
import org.skylark.langur.infrastructure.harness.workflow.WorkflowDefinitionRegistrar;
import org.skylark.langur.infrastructure.harness.workflow.WorkflowProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Harness 六组件装配。
 * <p>L 组件钩子引擎汇集所有 {@link LifecycleHook} 扩展点；
 * E 组件默认执行循环（ReAct）由领域服务 + 钩子引擎 + 状态仓储 + 观测服务 + 上下文组件协同构成。
 * 同时把钩子引擎与 T 组件统一调度器织入领域服务，覆盖工具调用拦截与四层校验链（§1.2 强制隔离）。</p>
 */
@Configuration
public class HarnessConfiguration {

    @Bean
    public LifecycleHookEngine lifecycleHookEngine(List<LifecycleHook> hooks) {
        LifecycleHookEngine engine = new LifecycleHookEngine();
        hooks.forEach(engine::register);
        return engine;
    }

    /**
     * E 组件底层循环 - ReAct（{@link RuntimeParadigm#REACT}）。
     * <p>把钩子引擎、T 组件统一调度器、容错能力、循环检测器与全链路追踪器织入领域服务/循环。
     * 同时作为中层 PlanAndExecute 的每步执行器（{@code stepExecutor}）复用。</p>
     */
    @Bean
    public ReActExecutionLoop reActExecutionLoop(AgentDomainService agentDomainService,
                                                 LifecycleHookEngine hookEngine,
                                                 TaskStateRepository taskStateRepository,
                                                 EvaluationService evaluationService,
                                                 ToolDispatcher toolDispatcher,
                                                 ContextAssembler contextAssembler,
                                                 AgentContextRepository contextRepository,
                                                 ExecutionTracer executionTracer) {
        // 将钩子引擎织入领域服务，覆盖 BEFORE/AFTER_TOOL_CALL 拦截点
        agentDomainService.attachHookEngine(hookEngine);
        // 将 T 组件统一调度器织入领域服务：工具调用必经四层校验链 + 沙箱（§1.2 模型与环境隔离）
        agentDomainService.attachToolDispatcher(toolDispatcher);
        // 织入容错能力（T7）：工具重试策略 + LLM/工具降级策略
        agentDomainService.attachResilience(RetryPolicy.defaults(), new DefaultFallbackStrategy());
        ReActExecutionLoop loop = new ReActExecutionLoop(agentDomainService, hookEngine, taskStateRepository,
                evaluationService, contextAssembler, contextRepository);
        // 织入循环检测器（T7）：连续 N 轮指纹重复超阈值强制终止（LOOP_DETECTED）
        loop.attachLoopDetector(LoopDetector.defaults());
        // 织入全链路追踪器（T12）：六类 Span 埋点（未启用时为 NOOP，零开销）
        loop.attachTracer(executionTracer);
        return loop;
    }

    /**
     * 规划器（H3）：{@code langur.planner.type=llm} 时装配 M5 LLM 规划（{@link LlmPlanner}），
     * 否则用领域默认启发式规划（{@link HeuristicPlanner}，零外部依赖、离线可测）。
     */
    @Bean
    public Planner planner(ObjectProvider<LlmPlanner> llmPlannerProvider) {
        LlmPlanner llmPlanner = llmPlannerProvider.getIfAvailable();
        return llmPlanner != null ? llmPlanner : new HeuristicPlanner();
    }

    /**
     * 执行进度端口（H3，§13.3）：适配应用层 {@link TaskProgressBus} 为领域进度出口；
     * 无进度总线时降级 NOOP（P10）。
     */
    @Bean
    public ExecutionProgressPort executionProgressPort(ObjectProvider<TaskProgressBus> progressBusProvider) {
        TaskProgressBus bus = progressBusProvider.getIfAvailable();
        return bus != null ? bus::publish : ExecutionProgressPort.NOOP;
    }

    /**
     * E 组件中层循环 - PlanAndExecute（{@link RuntimeParadigm#PLAN_AND_EXECUTE}，H3）。
     * <p>M5 规划拆解 → 每步委派 {@link ReActExecutionLoop} 子循环执行 → 进度回报 → 失败动态重规划/降级；
     * 复用底层终止闸门/钩子/快照，子步计量上卷主任务使全局闸门生效。</p>
     */
    @Bean
    public PlanAndExecuteExecutionLoop planAndExecuteExecutionLoop(ReActExecutionLoop reActExecutionLoop,
                                                                   Planner planner,
                                                                   TaskStateRepository taskStateRepository,
                                                                   EvaluationService evaluationService,
                                                                   LifecycleHookEngine hookEngine,
                                                                   ExecutionProgressPort progressPort) {
        PlanAndExecuteExecutionLoop loop = new PlanAndExecuteExecutionLoop(
                reActExecutionLoop, planner, taskStateRepository, evaluationService, hookEngine);
        loop.attachProgressPort(progressPort);
        return loop;
    }

    /**
     * 工作流定义仓储（H9，§5.3）：默认内存态注册表，并按 {@code langur.workflow.definitions} 配置
     * 注册确定性阶段序列（P9），使 WORKFLOW/HYBRID 多阶段编排在生产可运行。
     * 未注册的 bizCode 由 {@link WorkflowExecutionLoop} 合成兜底工作流（强合规 bizCode 自动加审批闸门）。
     */
    @Bean
    public WorkflowRepository workflowRepository(WorkflowProperties workflowProperties) {
        DefaultWorkflowRepository repository = new DefaultWorkflowRepository();
        new WorkflowDefinitionRegistrar().populate(repository, workflowProperties);
        return repository;
    }

    /**
     * E 组件顶层循环 - Workflow（{@link RuntimeParadigm#WORKFLOW}，H9）：Harness 全权驱动确定性阶段序列，
     * 审批闸门阶段经 {@link ApprovalPort} 挂起-恢复；每阶段委派底层 {@link ReActExecutionLoop} 执行。
     */
    @Bean
    public WorkflowExecutionLoop workflowExecutionLoop(ReActExecutionLoop reActExecutionLoop,
                                                       WorkflowRepository workflowRepository,
                                                       ObjectProvider<ApprovalPort> approvalPortProvider,
                                                       TaskStateRepository taskStateRepository,
                                                       EvaluationService evaluationService,
                                                       LifecycleHookEngine hookEngine,
                                                       ExecutionProgressPort progressPort) {
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(reActExecutionLoop, RuntimeParadigm.REACT,
                workflowRepository, approvalPortProvider.getIfAvailable(), taskStateRepository,
                evaluationService, hookEngine);
        loop.attachProgressPort(progressPort);
        return loop;
    }

    /**
     * E 组件分层混合循环 - Hybrid（{@link RuntimeParadigm#HYBRID}，H9）：顶层 Workflow 锁合规边界 →
     * 每阶段委派中层 {@link PlanAndExecuteExecutionLoop} 拆解 → 底层 ReAct 执行，三层分权贯通。
     */
    @Bean
    public WorkflowExecutionLoop hybridExecutionLoop(PlanAndExecuteExecutionLoop planAndExecuteExecutionLoop,
                                                     WorkflowRepository workflowRepository,
                                                     ObjectProvider<ApprovalPort> approvalPortProvider,
                                                     TaskStateRepository taskStateRepository,
                                                     EvaluationService evaluationService,
                                                     LifecycleHookEngine hookEngine,
                                                     ExecutionProgressPort progressPort) {
        WorkflowExecutionLoop loop = new WorkflowExecutionLoop(planAndExecuteExecutionLoop,
                RuntimeParadigm.PLAN_AND_EXECUTE, workflowRepository, approvalPortProvider.getIfAvailable(),
                taskStateRepository, evaluationService, hookEngine);
        loop.attachProgressPort(progressPort);
        return loop;
    }

    /**
     * E 组件统一入口 - 范式分发循环（H3/H9，§5.3）：按任务范式路由到 ReAct / PlanAndExecute / Workflow / Hybrid，
     * 未注册范式降级 ReAct。外层套 H4 分布式锁装饰器（跨实例互斥，后端由 {@code langur.cache.type} 决定），
     * 标记 {@link Primary} 供应用层按类型注入。
     */
    @Bean
    @Primary
    public ExecutionLoopService executionLoopService(ReActExecutionLoop reActExecutionLoop,
                                                     PlanAndExecuteExecutionLoop planAndExecuteExecutionLoop,
                                                     @Qualifier("workflowExecutionLoop") WorkflowExecutionLoop workflowExecutionLoop,
                                                     @Qualifier("hybridExecutionLoop") WorkflowExecutionLoop hybridExecutionLoop,
                                                     DistributedLock distributedLock,
                                                     CacheProperties cacheProperties) {
        Map<RuntimeParadigm, ExecutionLoopService> loops = new EnumMap<>(RuntimeParadigm.class);
        loops.put(RuntimeParadigm.REACT, reActExecutionLoop);
        loops.put(RuntimeParadigm.PLAN_AND_EXECUTE, planAndExecuteExecutionLoop);
        loops.put(RuntimeParadigm.WORKFLOW, workflowExecutionLoop);
        loops.put(RuntimeParadigm.HYBRID, hybridExecutionLoop);
        ExecutionLoopService dispatcher = new ParadigmDispatchingExecutionLoop(loops, reActExecutionLoop);
        return new LockingExecutionLoopService(dispatcher, distributedLock,
                Duration.ofSeconds(cacheProperties.getLockTtlSeconds()));
    }

    /**
     * C 组件 L4 - 向量记忆服务（T14 + H2 + H14.3）：嵌入端口 + 向量存储（默认内存实现，可切换 pgvector/ES/Milvus）
     * + 可选重排端口（{@code langur.rerank.enabled=true} 时装配 M3 重排去噪）
     * + 混合检索策略（{@code langur.vector.hybrid.*} → {@link HybridSearchOptions}；缺省 disabled，既有路径不变）。
     */
    @Bean
    public VectorMemoryService vectorMemoryService(EmbeddingPort embeddingPort,
                                                   VectorStore vectorStore,
                                                   ObjectProvider<RerankPort> rerankPortProvider,
                                                   VectorProperties vectorProperties) {
        return new VectorMemoryService(embeddingPort, vectorStore, rerankPortProvider.getIfAvailable(),
                toHybridOptions(vectorProperties.getHybrid()));
    }

    /** 将 {@code langur.vector.hybrid.*} 映射为 domain 纯值对象 {@link HybridSearchOptions}（P1 依赖倒置）。 */
    private static HybridSearchOptions toHybridOptions(VectorProperties.Hybrid hybrid) {
        if (hybrid == null || !hybrid.isEnabled()) {
            return HybridSearchOptions.disabled();
        }
        HybridSearchOptions.Mode mode = "app".equalsIgnoreCase(hybrid.getMode())
                ? HybridSearchOptions.Mode.APP : HybridSearchOptions.Mode.NATIVE;
        FusionMode fusion = "weighted".equalsIgnoreCase(hybrid.getFusion())
                ? FusionMode.WEIGHTED : FusionMode.RRF;
        return HybridSearchOptions.of(true, mode, fusion, hybrid.getRrfK(), hybrid.getLexicalWeight());
    }
}
