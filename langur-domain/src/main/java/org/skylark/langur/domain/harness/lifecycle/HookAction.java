package org.skylark.langur.domain.harness.lifecycle;

/**
 * Hook 返回动作
 */
public enum HookAction {
    /** 放行，继续执行 */
    CONTINUE,
    /** 中断，终止主链路 */
    ABORT,
    /** 跳过后续环节 */
    SKIP,
    /** 修改载荷后继续 */
    MODIFY
}
