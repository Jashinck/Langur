package org.skylark.langur.domain.harness.evaluation.alert;

/**
 * 告警分级（§10.3）- P0 致命 / P1 严重 / P2 警告 / P3 提示。
 * <p>每级绑定默认处置动作，供 {@link AlertChannel} 消费方决策（阻断/降级/终止/日报）。</p>
 */
public enum AlertLevel {

    /** P0 致命：资损/数据泄露 → 立即阻断 + 人工介入。 */
    P0_CRITICAL("BLOCK_AND_ESCALATE"),
    /** P1 严重：成功率<阈值/延迟>阈值 → 自动降级 + OnCall 通知。 */
    P1_SEVERE("DEGRADE_AND_NOTIFY"),
    /** P2 警告：Token 超限/循环检测 → 自动终止 + 记录。 */
    P2_WARNING("TERMINATE_AND_RECORD"),
    /** P3 提示：指标波动/容量预警 → 记录 + 日报汇总。 */
    P3_INFO("RECORD");

    private final String action;

    AlertLevel(String action) {
        this.action = action;
    }

    public String defaultAction() {
        return action;
    }
}
