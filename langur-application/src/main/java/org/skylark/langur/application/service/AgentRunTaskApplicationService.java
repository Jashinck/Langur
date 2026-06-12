package org.skylark.langur.application.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.dto.RunTaskResult;
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
                command.getSessionId());
        taskRepository.save(task);

        CompletableFuture.runAsync(() -> executeTask(task, command), executorService);
        return toResult(task);
    }

    public RunTaskResult getTask(String taskId) {
        AgentRunTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toResult(task);
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

            AgentResult response = agentApplicationService.runAgent(command);
            task.markCompleted("Agent status: " + response.getStatus());
        } catch (Exception e) {
            log.error("Async agent run failed for task {}", task.getTaskId(), e);
            task.markFailed(e.getMessage());
        } finally {
            taskRepository.save(task);
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
