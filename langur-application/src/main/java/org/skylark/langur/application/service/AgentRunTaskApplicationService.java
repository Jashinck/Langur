package org.skylark.langur.application.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.dto.RunTaskResult;
import org.skylark.langur.application.stream.TaskProgressBus;
import org.skylark.langur.common.exception.TaskNotFoundException;
import org.skylark.langur.domain.model.execution.AgentRunTask;
import org.skylark.langur.domain.repository.AgentRunTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunTaskApplicationService {

    private final AgentRunTaskRepository taskRepository;
    private final AgentApplicationService agentApplicationService;
    private final TaskProgressBus progressBus;

    @Value("${langur.run-task.executor-size:4}")
    private int executorSize;

    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        this.executorService = Executors.newFixedThreadPool(executorSize);
    }

    public RunTaskResult startAsyncRun(RunAgentCommand command) {
        AgentRunTask task = AgentRunTask.create(
                command.getAgentId(),
                command.getUserId(),
                command.getTenantId(),
                command.getSessionId(),
                command.getUserMessage());
        taskRepository.save(task);

        CompletableFuture.runAsync(() -> executeTask(task, command), executorService);
        return toResult(task);
    }

    /**
     * 创建一个待执行任务（不立即执行），供流式入口"先注册监听后执行"（T8）。
     */
    public RunTaskResult createPendingTask(RunAgentCommand command) {
        AgentRunTask task = AgentRunTask.create(
                command.getAgentId(),
                command.getUserId(),
                command.getTenantId(),
                command.getSessionId(),
                command.getUserMessage());
        taskRepository.save(task);
        return toResult(task);
    }

    /**
     * 异步触发既有任务执行（T8），进度事件经 {@link TaskProgressBus} 发布。
     */
    public void executeAsync(String taskId, RunAgentCommand command) {
        AgentRunTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        CompletableFuture.runAsync(() -> executeTask(task, command), executorService);
    }

    public RunTaskResult getTask(String taskId) {
        AgentRunTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        return toResult(task);
    }

    /**
     * 任务控制面：取消任务（仅未完成任务可取消）
     */
    public RunTaskResult cancelTask(String taskId) {
        AgentRunTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getStatus().isTerminal()) {
            throw new IllegalStateException("Task already in terminal state: " + task.getStatus());
        }
        task.markCancelled("Cancelled by control plane");
        taskRepository.save(task);
        return toResult(task);
    }

    /**
     * 任务控制面：重试失败任务（基于原请求派生新任务）
     */
    public RunTaskResult retryTask(String taskId) {
        AgentRunTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (!task.isRetryable()) {
            throw new IllegalStateException("Task not retryable in state: " + task.getStatus());
        }
        RunAgentCommand command = RunAgentCommand.builder()
                .agentId(task.getAgentId())
                .userMessage(task.getUserMessage())
                .userId(task.getUserId())
                .tenantId(task.getTenantId())
                .sessionId(task.getSessionId())
                .build();
        return startAsyncRun(command);
    }

    public List<RunTaskResult> listTasksByAgent(String agentId) {
        return taskRepository.findByAgentId(agentId).stream()
                .map(this::toResult)
                .toList();
    }

    private void executeTask(AgentRunTask task, RunAgentCommand command) {
        try {
            task.markRunning();
            taskRepository.save(task);
            progressBus.publish(task.getTaskId(), "progress", "RUNNING");

            AgentResult response = agentApplicationService.runAgent(command);
            task.markCompleted("Agent status: " + response.getStatus());
            progressBus.publish(task.getTaskId(), "summary", response.getStatus());
        } catch (Exception e) {
            log.error("Async agent run failed for task {}", task.getTaskId(), e);
            task.markFailed(e.getMessage());
            progressBus.publish(task.getTaskId(), "summary", "FAILED");
        } finally {
            taskRepository.save(task);
            progressBus.publish(task.getTaskId(), "done", task.getStatus().name());
            progressBus.complete(task.getTaskId());
        }
    }

    private RunTaskResult toResult(AgentRunTask task) {
        return RunTaskResult.builder()
                .taskId(task.getTaskId())
                .agentId(task.getAgentId())
                .status(task.getStatus().name())
                .resultSummary(task.getResultSummary())
                .lastError(task.getLastError())
                .userId(task.getUserId())
                .tenantId(task.getTenantId())
                .sessionId(task.getSessionId())
                .createdAt(task.getCreatedAt().toString())
                .updatedAt(task.getUpdatedAt().toString())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        if (executorService == null) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }
}
