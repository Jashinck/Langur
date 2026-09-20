package org.skylark.langur.application.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.RunTaskResult;
import org.skylark.langur.application.service.AgentApplicationService;
import org.skylark.langur.application.service.AgentRunTaskApplicationService;
import org.springframework.stereotype.Service;

/**
 * 流式接入应用服务（§13.1 / T8）：编排 SSE 双入口。
 * <ul>
 *   <li>流式对话：message → summary → done</li>
 *   <li>长任务流式：accepted → progress → summary → done（先注册监听后执行）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStreamApplicationService {

    private final AgentApplicationService agentApplicationService;
    private final AgentRunTaskApplicationService runTaskApplicationService;
    private final TaskProgressBus progressBus;

    public void streamChat(RunAgentCommand command, StreamEventHandler handler) {
        agentApplicationService.streamChat(command, handler);
    }

    public void streamTask(RunAgentCommand command, StreamEventHandler handler) {
        RunTaskResult created = runTaskApplicationService.createPendingTask(command);
        String taskId = created.getTaskId();
        // 先注册监听，再触发异步执行，确保早期进度事件不丢失
        progressBus.register(taskId, handler);
        handler.send("accepted", taskId);
        runTaskApplicationService.executeAsync(taskId, command);
    }
}
