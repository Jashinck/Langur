package org.skylark.langur.application.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.domain.model.execution.AgentRunTask;
import org.skylark.langur.domain.repository.AgentRunTaskRepository;
import org.skylark.langur.interfaces.dto.AgentResponse;
import org.skylark.langur.interfaces.dto.RunTaskResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunTaskApplicationService {

    private final AgentRunTaskRepository taskRepository;
    private final AgentApplicationService agentApplicationService;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    public RunTaskResponse startAsyncRun(RunAgentCommand command) {
        AgentRunTask task = AgentRunTask.create(
                command.getAgentId(),
                command.getUserId(),
                command.getTenantId(),
                command.getSessionId());
        taskRepository.save(task);

        CompletableFuture.runAsync(() -> executeTask(task, command), executorService);
        return toResponse(task);
    }

    public RunTaskResponse getTask(String taskId) {
        AgentRunTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toResponse(task);
    }

    public List<RunTaskResponse> listTasksByAgent(String agentId) {
        return taskRepository.findByAgentId(agentId).stream()
                .map(this::toResponse)
                .toList();
    }

    private void executeTask(AgentRunTask task, RunAgentCommand command) {
        try {
            task.markRunning();
            taskRepository.save(task);

            AgentResponse response = agentApplicationService.runAgent(command);
            task.markCompleted("Agent status: " + response.getStatus());
        } catch (Exception e) {
            log.error("Async agent run failed for task {}", task.getTaskId(), e);
            task.markFailed(e.getMessage());
        } finally {
            taskRepository.save(task);
        }
    }

    private RunTaskResponse toResponse(AgentRunTask task) {
        return RunTaskResponse.builder()
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
        executorService.shutdownNow();
    }
}
