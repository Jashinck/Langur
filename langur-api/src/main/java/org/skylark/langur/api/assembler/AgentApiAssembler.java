package org.skylark.langur.api.assembler;

import org.skylark.langur.api.dto.AgentResponse;
import org.skylark.langur.api.dto.PlanResponse;
import org.skylark.langur.api.dto.PlanStepResponse;
import org.skylark.langur.api.dto.RunTaskResponse;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.dto.PlanResult;
import org.skylark.langur.application.dto.RunTaskResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentApiAssembler {

    public AgentResponse toResponse(AgentResult result) {
        return AgentResponse.builder()
                .id(result.getId())
                .name(result.getName())
                .description(result.getDescription())
                .status(result.getStatus())
                .model(result.getModel())
                .iterationCount(result.getIterationCount())
                .toolCount(result.getToolCount())
                .lastError(result.getLastError())
                .createdAt(result.getCreatedAt())
                .updatedAt(result.getUpdatedAt())
                .build();
    }

    public RunTaskResponse toResponse(RunTaskResult result) {
        return RunTaskResponse.builder()
                .taskId(result.getTaskId())
                .agentId(result.getAgentId())
                .status(result.getStatus())
                .resultSummary(result.getResultSummary())
                .lastError(result.getLastError())
                .userId(result.getUserId())
                .tenantId(result.getTenantId())
                .sessionId(result.getSessionId())
                .createdAt(result.getCreatedAt())
                .updatedAt(result.getUpdatedAt())
                .build();
    }

    public PlanResponse toResponse(PlanResult result) {
        List<PlanStepResponse> steps = result.getSteps().stream()
                .map(step -> PlanStepResponse.builder()
                        .index(step.getIndex())
                        .thought(step.getThought())
                        .action(step.getAction())
                        .actionInput(step.getActionInput())
                        .observation(step.getObservation())
                        .status(step.getStatus())
                        .build())
                .toList();
        return PlanResponse.builder()
                .agentId(result.getAgentId())
                .currentStepIndex(result.getCurrentStepIndex())
                .steps(steps)
                .build();
    }
}
