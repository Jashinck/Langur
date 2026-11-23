package org.skylark.langur.domain.harness.rsi;

/**
 * 工具自扩展裁定（R5）。{@link ToolExtensionValidator} 对候选工具的一票否决/放行结论。
 * 纯 JDK record，零外部依赖（P1）。</p>
 */
public record ToolExtensionVerdict(Reason reason, String detail) {

    public enum Reason {
        /** 放行：合法且（高危）已人审。 */
        ACCEPTED,
        /** 空/非法候选。 */
        REJECTED_EMPTY,
        /** 不安全端点（内网/环回，SSRF 红线）。 */
        REJECTED_UNSAFE_ENDPOINT,
        /** 高危未经人审（P11 红线）。 */
        REJECTED_CRITICAL_WITHOUT_APPROVAL
    }

    public ToolExtensionVerdict {
        reason = (reason == null) ? Reason.REJECTED_EMPTY : reason;
        detail = (detail == null) ? "" : detail;
    }

    public boolean isAccepted() {
        return reason == Reason.ACCEPTED;
    }

    public static ToolExtensionVerdict accepted() {
        return new ToolExtensionVerdict(Reason.ACCEPTED, "");
    }

    public static ToolExtensionVerdict rejected(Reason reason, String detail) {
        return new ToolExtensionVerdict(reason, detail);
    }
}
