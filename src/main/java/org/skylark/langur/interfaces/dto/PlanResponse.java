package org.skylark.langur.interfaces.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PlanResponse {
    private String agentId;
    private int currentStepIndex;
    private List<PlanStepResponse> steps;
}
