package org.skylark.langur.common.spi;

/**
 * SPI #2 Prompt 模板 - 业务专属 System Prompt 构建（§11.1）。
 * <p>注册方式：{@code @Component + getBizCode()}。</p>
 */
public interface PromptTemplateSPI {

    /** 归属业务域标识。 */
    String getBizCode();

    /** 基于业务上下文构建 Prompt 文本。 */
    String buildPrompt(BizContext context);
}
