package org.skylark.langur.infrastructure.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link SkillToolGateway} 默认实现 - 从 {@link SkillCatalog} 取规格交由 {@link SkillExecutor} 执行。
 * <p>{@link ToolDispatcher} 以 {@code @Lazy} 注入打破"调度器→技能网关→调度器"的构造期循环依赖；
 * 技能内部每步工具调用仍回派调度器，因而嵌套受四层校验链管控。</p>
 * <p>H8：{@link LlmGateway} 适配为 {@link SkillLlmPort} 支撑 {@code LLM_CALL}（角色名 → {@link ModelRole}）；
 * {@link SubAgentInvoker} 为可选依赖，缺失时 {@code SUB_AGENT} 步骤抛出明确异常。二者均以 {@link ObjectProvider}
 * 松耦合注入，技能引擎在无 LLM/子 Agent 环境下仍可执行 TOOL_CALL/CONDITION/LOOP/PARALLEL/SUB_WORKFLOW。</p>
 */
@Component
public class DefaultSkillToolGateway implements SkillToolGateway {

    private final SkillCatalog catalog;
    private final SkillExecutor executor;

    public DefaultSkillToolGateway(SkillCatalog catalog,
                                   @Lazy ToolDispatcher dispatcher,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<LlmGateway> llmGatewayProvider,
                                   ObjectProvider<SubAgentInvoker> subAgentInvokerProvider) {
        this.catalog = catalog;
        SkillLlmPort llmPort = toLlmPort(llmGatewayProvider.getIfAvailable());
        this.executor = new SkillExecutor(dispatcher, new SkillExpressionResolver(objectMapper),
                llmPort, subAgentInvokerProvider.getIfAvailable(), null);
    }

    /** 将 {@link LlmGateway} 适配为 {@link SkillLlmPort}：角色名解析为 {@link ModelRole}，未知角色回退 ACTION。 */
    private static SkillLlmPort toLlmPort(LlmGateway gateway) {
        if (gateway == null) {
            return null;
        }
        return (modelRole, systemPrompt, userPrompt) ->
                gateway.complete(resolveRole(modelRole), systemPrompt, userPrompt);
    }

    private static ModelRole resolveRole(String name) {
        if (name == null || name.isBlank()) {
            return ModelRole.ACTION;
        }
        try {
            return ModelRole.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ModelRole.ACTION;
        }
    }

    @Override
    public boolean supports(String toolId) {
        return catalog.find(toolId).isPresent();
    }

    @Override
    public String execute(String toolId, Map<String, Object> arguments) {
        SkillSpec spec = catalog.find(toolId)
                .orElseThrow(() -> new SkillExecutionException("skill spec not found: " + toolId));
        return executor.execute(spec, arguments);
    }
}
