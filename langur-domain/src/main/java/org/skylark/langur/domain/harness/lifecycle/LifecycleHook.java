package org.skylark.langur.domain.harness.lifecycle;

/**
 * 生命周期钩子 - SPI 扩展点（非侵入、可动态启停）
 */
public interface LifecycleHook {

    HookPoint point();

    HookResult execute(HookContext context);

    default int order() {
        return 0;
    }

    default boolean enabled() {
        return true;
    }
}
