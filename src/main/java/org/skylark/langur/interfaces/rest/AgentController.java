package org.skylark.langur.interfaces.rest;

import lombok.RequiredArgsConstructor;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.CreateAgentCommand;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.service.AgentApplicationService;
import org.skylark.langur.application.service.AgentRunTaskApplicationService;
import org.skylark.langur.domain.model.message.MessagePartType;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.interfaces.dto.AgentResponse;
import org.skylark.langur.interfaces.dto.CreateAgentRequest;
import org.skylark.langur.interfaces.dto.PlanResponse;
import org.skylark.langur.interfaces.dto.PlanStepResponse;
import org.skylark.langur.interfaces.dto.RunAgentRequest;
import org.skylark.langur.interfaces.dto.RunTaskResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentApplicationService agentApplicationService;
    private final AgentRunTaskApplicationService runTaskApplicationService;

    @PostMapping
    public ResponseEntity<AgentResponse> createAgent(@RequestBody CreateAgentRequest request) {
        CreateAgentCommand command = CreateAgentCommand.builder()
                .name(request.getName())
                .description(request.getDescription())
                .systemPrompt(request.getSystemPrompt())
                .model(request.getModel())
                .temperature(request.getTemperature())
                .maxIterations(request.getMaxIterations())
                .toolNames(request.getToolNames())
                .build();
        return ResponseEntity.ok(agentApplicationService.createAgent(command));
    }

    @PostMapping("/{agentId}/run")
    public ResponseEntity<AgentResponse> runAgent(
            @PathVariable String agentId,
            @RequestBody RunAgentRequest request) {
        RunAgentCommand command = RunAgentCommand.builder()
                .agentId(agentId)
                .userMessage(request.getUserMessage())
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .sessionId(request.getSessionId())
                .messageParts(toMessagePartInputs(request))
                .build();
        return ResponseEntity.ok(agentApplicationService.runAgent(command));
    }

    @PostMapping("/{agentId}/run/async")
    public ResponseEntity<RunTaskResponse> runAgentAsync(
            @PathVariable String agentId,
            @RequestBody RunAgentRequest request) {
        RunAgentCommand command = RunAgentCommand.builder()
                .agentId(agentId)
                .userMessage(request.getUserMessage())
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .sessionId(request.getSessionId())
                .messageParts(toMessagePartInputs(request))
                .build();
        return ResponseEntity.accepted().body(runTaskApplicationService.startAsyncRun(command));
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentResponse> getAgent(@PathVariable String agentId) {
        return ResponseEntity.ok(agentApplicationService.getAgent(agentId));
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents() {
        return ResponseEntity.ok(agentApplicationService.listAgents());
    }

    @GetMapping("/runs/{taskId}")
    public ResponseEntity<RunTaskResponse> getRunTask(@PathVariable String taskId) {
        return ResponseEntity.ok(runTaskApplicationService.getTask(taskId));
    }

    @GetMapping("/{agentId}/runs")
    public ResponseEntity<List<RunTaskResponse>> listRunTasks(@PathVariable String agentId) {
        return ResponseEntity.ok(runTaskApplicationService.listTasksByAgent(agentId));
    }

    @GetMapping("/{agentId}/plan")
    public ResponseEntity<PlanResponse> getLatestPlan(@PathVariable String agentId) {
        Plan plan = agentApplicationService.getLatestPlan(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found for agent: " + agentId));
        List<PlanStepResponse> steps = plan.getSteps().stream()
                .map(step -> PlanStepResponse.builder()
                        .index(step.getIndex())
                        .thought(step.getThought())
                        .action(step.getAction())
                        .actionInput(step.getActionInput())
                        .observation(step.getObservation())
                        .status(step.getStatus().name())
                        .build())
                .toList();
        return ResponseEntity.ok(PlanResponse.builder()
                .agentId(plan.getAgentId())
                .currentStepIndex(plan.getCurrentStepIndex())
                .steps(steps)
                .build());
    }

    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String agentId) {
        agentApplicationService.deleteAgent(agentId);
        return ResponseEntity.noContent().build();
    }

    private List<MessagePartInput> toMessagePartInputs(RunAgentRequest request) {
        if (request.getMessageParts() == null || request.getMessageParts().isEmpty()) {
            return List.of();
        }
        return request.getMessageParts().stream()
                .map(part -> MessagePartInput.builder()
                        .type(MessagePartType.fromValue(part.getType()))
                        .content(part.getContent())
                        .mediaUrl(part.getMediaUrl())
                        .build())
                .toList();
    }
}