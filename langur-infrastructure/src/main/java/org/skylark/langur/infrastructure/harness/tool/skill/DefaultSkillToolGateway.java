package org.skylark.langur.infrastructure.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link SkillToolGateway} 默认实现 - 从 {@link SkillCatalog} 取规格交由 {@link SkillExecutor} 执行。
 * <p>{@link ToolDispatcher} 以 {@code @Lazy} 注入打破"调度器→技能网关→调度器"的构造期循环依赖；
 * 技能内部每步工具调用仍回派调度器，因而嵌套受四层校验链管控。</p>
 */
@Component
public class DefaultSkillToolGateway implements SkillToolGateway {

    private final SkillCatalog catalog;
    private final SkillExecutor executor;

    public DefaultSkillToolGateway(SkillCatalog catalog, @Lazy ToolDispatcher dispatcher, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.executor = new SkillExecutor(dispatcher, new SkillExpressionResolver(objectMapper));
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
