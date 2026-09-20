package org.skylark.langur.common.spi;

/**
 * SPI #7 输出后处理器 - 业务输出格式化（§11.1）。
 * <p>注册方式：{@code @Component + getOrder()}；按 order 升序对输出链式加工。</p>
 */
public interface OutputPostProcessorSPI {

    /** 执行顺序，值小者先。 */
    default int getOrder() {
        return 0;
    }

    /** 对输出文本做业务格式化/改写，返回处理后的文本。 */
    String process(String output, BizContext context);
}
