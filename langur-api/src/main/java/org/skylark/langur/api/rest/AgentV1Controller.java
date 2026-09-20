package org.skylark.langur.api.rest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.skylark.langur.api.assembler.AgentApiAssembler;
import org.skylark.langur.api.dto.ChatRequest;
import org.skylark.langur.api.dto.ChatResponse;
import org.skylark.langur.api.dto.MessagePartRequest;
import org.skylark.langur.api.dto.Result;
import org.skylark.langur.api.dto.RunTaskResponse;
import org.skylark.langur.api.governance.RequestContext;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.service.AgentApplicationService;
import org.skylark.langur.application.service.AgentRunTaskApplicationService;
import org.skylark.langur.domain.model.message.MessagePartType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v1 接入体系（对齐架构设计 13：三大入口 + 任务控制面 + 统一 Result）。
 * <p>同步对话与任务控制面；统一返回 {@link Result}，异常由 {@link GlobalExceptionHandler} 兜底。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentV1Controller {

    private final AgentApplicationService agentApplicationService;
    private final AgentRunTaskApplicationService runTaskApplicationService;
    private final AgentApiAssembler apiAssembler;

    // ============ 13.1 同步对话入口 ============

    /**
     * 同步对话：单轮问答/短任务，请求-响应模式。
     */
    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        AgentResult result = agentApplicationService.runAgent(toCommand(request));
        ChatResponse response = ChatResponse.builder()
                .agentId(result.getId())
                .status(result.getStatus())
                .answer(result.getAnswer())
                .lastError(result.getLastError())
                .build();
        return Result.success(response);
    }

    // ============ 13.2 任务控制面 ============

    /**
     * 创建长任务（异步执行，返回任务句柄）。
     */
    @PostMapping("/task/create")
    public Result<RunTaskResponse> createTask(@Valid @RequestBody ChatRequest request) {
        return Result.success(apiAssembler.toResponse(runTaskApplicationService.startAsyncRun(toCommand(request))));
    }

    /**
     * 查询任务状态。
     */
    @GetMapping("/task/{taskId}/status")
    public Result<RunTaskResponse> taskStatus(@PathVariable String taskId) {
        return Result.success(apiAssembler.toResponse(runTaskApplicationService.getTask(taskId)));
    }

    /**
     * 取消任务（仅未完成任务可取消）。
     */
    @PostMapping("/task/{taskId}/cancel")
    public Result<RunTaskResponse> cancelTask(@PathVariable String taskId) {
        return Result.success(apiAssembler.toResponse(runTaskApplicationService.cancelTask(taskId)));
    }

    /**
     * 重试失败任务（基于原请求派生新任务）。
     */
    @PostMapping("/task/{taskId}/retry")
    public Result<RunTaskResponse> retryTask(@PathVariable String taskId) {
        return Result.success(apiAssembler.toResponse(runTaskApplicationService.retryTask(taskId)));
    }

    private RunAgentCommand toCommand(ChatRequest request) {
        return RunAgentCommand.builder()
                .agentId(request.getAgentId())
                .userMessage(request.getUserMessage())
                // 治理头透传（§13.3）：可信 user/tenant 覆盖业务入参
                .userId(trustedOrRaw(RequestContext.userId(), request.getUserId()))
                .tenantId(trustedOrRaw(RequestContext.tenantId(), request.getTenantId()))
                .sessionId(request.getSessionId())
                .bizCode(request.getBizCode())
                .messageParts(toMessagePartInputs(request.getMessageParts()))
                .build();
    }

    private String trustedOrRaw(String trusted, String raw) {
        return (trusted != null && !trusted.isBlank()) ? trusted : raw;
    }

    private List<MessagePartInput> toMessagePartInputs(List<MessagePartRequest> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        return parts.stream()
                .map(part -> MessagePartInput.builder()
                        .type(MessagePartType.fromValue(part.getType()))
                        .content(part.getContent())
                        .mediaUrl(part.getMediaUrl())
                        .build())
                .toList();
    }
}
