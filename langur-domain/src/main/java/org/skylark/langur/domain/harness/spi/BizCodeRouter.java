package org.skylark.langur.domain.harness.spi;

import org.skylark.langur.common.spi.BusinessExecutorSPI;
import org.skylark.langur.common.spi.ContextEnricherSPI;
import org.skylark.langur.common.spi.DecisionEngineSPI;
import org.skylark.langur.common.spi.OutputPostProcessorSPI;
import org.skylark.langur.common.spi.PromptTemplateSPI;
import org.skylark.langur.common.spi.SecurityPolicySPI;
import org.skylark.langur.common.spi.ToolProviderSPI;

import java.util.List;
import java.util.Optional;

/**
 * BizCode 路由器（端口）- 按业务域标识匹配七大 SPI 实现（§11.2）。
 * <p>Domain 仅定义契约（依赖倒置），聚合与匹配由 infra 实现、start 装配。
 * bizCode 无精确匹配时回退 {@code default} 业务域，仍无则返回空。</p>
 */
public interface BizCodeRouter {

    String DEFAULT_BIZ_CODE = "default";

    Optional<BusinessExecutorSPI> executor(String bizCode);

    Optional<PromptTemplateSPI> promptTemplate(String bizCode);

    Optional<ToolProviderSPI> toolProvider(String bizCode);

    Optional<SecurityPolicySPI> securityPolicy(String bizCode);

    Optional<DecisionEngineSPI> decisionEngine(String bizCode);

    /** 上下文增强器（全局，按 order 升序）。 */
    List<ContextEnricherSPI> contextEnrichers();

    /** 输出后处理器（全局，按 order 升序）。 */
    List<OutputPostProcessorSPI> outputPostProcessors();
}
