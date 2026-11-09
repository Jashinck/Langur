package org.skylark.langur;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.ContextBuilder;
import org.skylark.langur.domain.harness.execution.ReActExecutionLoop;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHookEngine;
import org.skylark.langur.domain.harness.state.TaskStateRepository;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.service.AgentDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H15.8 装配冒烟：加载完整 Spring 上下文，断言六组件关键 Bean 均已装配——
 * 堵住"文档标 ✅ 但 Bean 未装配"的历史欠账（如 H12 消息总线仅测试装配）。
 */
@SpringBootTest
class HarnessAssemblySmokeTest {

    @Autowired
    private AgentDomainService agentDomainService;
    @Autowired
    private ReActExecutionLoop reActExecutionLoop;
    @Autowired
    private LifecycleHookEngine lifecycleHookEngine;
    @Autowired
    private ToolDispatcher toolDispatcher;
    @Autowired
    private TaskStateRepository taskStateRepository;
    @Autowired
    private LLMPort llmPort;
    @Autowired
    private ContextBuilder contextBuilder;

    @Test
    void keyHarnessBeansAreWired() {
        assertNotNull(agentDomainService, "E/T 装配的领域服务缺失");
        assertNotNull(reActExecutionLoop, "E 组件执行循环缺失");
        assertNotNull(lifecycleHookEngine, "L 组件钩子引擎缺失");
        assertNotNull(toolDispatcher, "T 组件统一调度器缺失");
        assertNotNull(taskStateRepository, "S 组件状态仓储缺失");
        assertNotNull(llmPort, "统一模型网关缺失");
        assertNotNull(contextBuilder, "C 组件上下文构建器（H15.2）缺失");
    }
}
