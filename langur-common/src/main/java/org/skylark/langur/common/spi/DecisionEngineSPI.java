package org.skylark.langur.common.spi;

import java.util.Optional;

/**
 * SPI #6 决策引擎 - 业务决策规则匹配（§11.1）。
 * <p>注册方式：{@code @Component + getBizCode()}；返回决策标识（如范式提示、分支选择）。</p>
 */
public interface DecisionEngineSPI {

    /** 归属业务域标识。 */
    String getBizCode();

    /** 依据上下文匹配业务规则，返回决策结果；无匹配返回空。 */
    Optional<String> decide(BizContext context);
}
