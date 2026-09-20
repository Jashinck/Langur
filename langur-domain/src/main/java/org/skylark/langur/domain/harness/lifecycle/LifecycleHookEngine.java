package org.skylark.langur.domain.harness.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * L 组件 - 生命周期钩子引擎。
 * <p>纯领域实现：按 order 顺序执行命中拦截点的 Hook 链，
 * 支持 ABORT 中断、SKIP 跳过、MODIFY 改写载荷、CONTINUE 放行。</p>
 */
public class LifecycleHookEngine {

    private final List<LifecycleHook> hooks = new ArrayList<>();

    public void register(LifecycleHook hook) {
        hooks.add(hook);
        hooks.sort(Comparator.comparingInt(LifecycleHook::order));
    }

    /**
     * 触发指定拦截点的 Hook 链。
     *
     * @return 链终态：ABORT 需中断主链路；其余可继续
     */
    public HookResult fire(HookContext context) {
        for (LifecycleHook hook : hooks) {
            if (!hook.enabled() || hook.point() != context.getPoint()) {
                continue;
            }
            HookResult result = hook.execute(context);
            if (result == null) {
                continue;
            }
            switch (result.getAction()) {
                case ABORT, SKIP -> {
                    return result;
                }
                case MODIFY -> {
                    if (result.getModifiedPayload() != null) {
                        context.setPayload(result.getModifiedPayload());
                    }
                }
                case CONTINUE -> {
                    // 放行，继续执行下一个 Hook
                }
            }
        }
        return HookResult.continueFlow();
    }
}
