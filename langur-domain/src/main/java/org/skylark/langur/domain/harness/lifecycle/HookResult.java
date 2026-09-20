package org.skylark.langur.domain.harness.lifecycle;

import lombok.Getter;

/**
 * Hook 执行结果 - 承载动作、原因与修改后的载荷
 */
@Getter
public class HookResult {

    private final HookAction action;
    private final String message;
    private final String modifiedPayload;

    private HookResult(HookAction action, String message, String modifiedPayload) {
        this.action = action;
        this.message = message;
        this.modifiedPayload = modifiedPayload;
    }

    public static HookResult continueFlow() {
        return new HookResult(HookAction.CONTINUE, null, null);
    }

    public static HookResult abort(String reason) {
        return new HookResult(HookAction.ABORT, reason, null);
    }

    public static HookResult skip() {
        return new HookResult(HookAction.SKIP, null, null);
    }

    public static HookResult modify(String newPayload) {
        return new HookResult(HookAction.MODIFY, null, newPayload);
    }
}
