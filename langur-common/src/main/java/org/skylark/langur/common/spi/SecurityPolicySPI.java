package org.skylark.langur.common.spi;

/**
 * SPI #4 安全策略 - 业务自定义安全规则（§11.1 / §9 五层纵深防御之业务扩展）。
 * <p>注册方式：{@code @Component + getBizCode()}。</p>
 */
public interface SecurityPolicySPI {

    /** 归属业务域标识。 */
    String getBizCode();

    /** 判定给定动作在当前上下文是否放行。 */
    boolean isAllowed(String action, BizContext context);

    /** 拒绝原因（默认话术，可覆写用于审计/告警）。 */
    default String denyReason(String action, BizContext context) {
        return "action [" + action + "] denied by security policy of bizCode [" + getBizCode() + "]";
    }
}
