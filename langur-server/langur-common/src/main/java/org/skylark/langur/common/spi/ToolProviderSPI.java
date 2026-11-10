package org.skylark.langur.common.spi;

import java.util.List;
import java.util.Map;

/**
 * SPI #3 工具提供者 - 业务工具注册 + 执行（§11.1 / §7.1 SPI 来源）。
 * <p>注册方式：{@code @ToolProvider(bizCode)}；工具经 T 组件四层校验链统一管控。</p>
 */
public interface ToolProviderSPI {

    /** 归属业务域标识。 */
    String getBizCode();

    /** 声明本业务域可被 LLM 调用的工具集。 */
    List<SpiToolSpec> getTools();

    /** 执行指定工具，返回序列化结果字符串。 */
    String execute(String toolId, Map<String, Object> arguments, BizContext context);
}
