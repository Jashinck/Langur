package org.skylark.langur.config;

import org.skylark.langur.domain.harness.context.AgentContextRepository;
import org.skylark.langur.domain.harness.context.ContextAssembler;
import org.skylark.langur.domain.harness.context.vector.EmbeddingPort;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorStore;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.execution.ExecutionLoopService;
import org.skylark.langur.domain.harness.execution.LoopDetector;
import org.skylark.langur.domain.harness.execution.ReActExecutionLoop;
import org.skylark.langur.domain.harness.execution.RetryPolicy;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.port.DefaultFallbackStrategy;
import org.skylark.langur.domain.service.AgentDomainService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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

    @Bean
    public ExecutionLoopService executionLoopService(AgentDomainService agentDomainService,
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
     * C 组件 L4 - 向量记忆服务（T14）：嵌入端口 + 向量存储（默认内存实现，可切换 pgvector）。
     */
    @Bean
    public VectorMemoryService vectorMemoryService(EmbeddingPort embeddingPort, VectorStore vectorStore) {
        return new VectorMemoryService(embeddingPort, vectorStore);
    }
}
