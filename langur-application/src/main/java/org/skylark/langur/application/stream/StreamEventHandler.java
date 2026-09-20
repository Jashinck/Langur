package org.skylark.langur.application.stream;

/**
 * 流式事件回调（T8）：应用层产出事件，由 API 层适配为 SSE 写出。
 */
public interface StreamEventHandler {

    /** 发送一个命名事件（如 message / summary / progress / accepted / done） */
    void send(String event, String data);

    /** 流正常结束 */
    void complete();

    /** 流异常结束 */
    void fail(Throwable error);
}
