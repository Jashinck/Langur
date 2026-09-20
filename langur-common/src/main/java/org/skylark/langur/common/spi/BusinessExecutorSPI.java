package org.skylark.langur.common.spi;

/**
 * SPI #1 业务执行器 - 承载业务节点的自定义执行逻辑（§11.1）。
 * <p>注册方式：{@code @BizExecutor(bizCode)}；由 {@code BizCodeRouter} 按 bizCode 匹配。</p>
 */
public interface BusinessExecutorSPI {

    /** 归属业务域标识。 */
    String getBizCode();

    /** 执行业务节点，返回业务结果（由调用方解释）。 */
    Object execute(BizContext context);
}
