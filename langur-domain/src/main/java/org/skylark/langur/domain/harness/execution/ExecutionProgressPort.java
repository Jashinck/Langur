package org.skylark.langur.domain.harness.execution;

/**
 * 执行进度端口（H3，§13.3）- PlanAndExecute 逐步回报进度的领域出口。
 * <p>纯领域端口（零外部依赖，P1）；应用层以 {@code TaskProgressBus::publish} 适配为 SSE 进度事件。
 * 进度上报失败不得影响主执行链路（由实现方吞异常，P10）。</p>
 */
public interface ExecutionProgressPort {

    /**
     * 发布一条进度事件。
     *
     * @param taskId 执行任务标识
     * @param event  事件名（如 plan / progress / replan / degrade）
     * @param data   事件负载
     */
    void publish(String taskId, String event, String data);

    /** 空实现：丢弃全部进度（未装配进度总线时的降级兜底）。 */
    ExecutionProgressPort NOOP = (taskId, event, data) -> {
    };
}
