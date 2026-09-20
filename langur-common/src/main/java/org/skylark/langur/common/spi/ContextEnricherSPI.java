package org.skylark.langur.common.spi;

/**
 * SPI #5 上下文增强器 - 业务上下文数据加载（§11.1）。
 * <p>注册方式：{@code @Component + getOrder()}；按 order 升序执行，向 {@link BizContext} 属性袋写数据。</p>
 */
public interface ContextEnricherSPI {

    /** 执行顺序，值小者先。 */
    default int getOrder() {
        return 0;
    }

    /** 加载业务数据写入上下文属性袋。 */
    void enrich(BizContext context);
}
