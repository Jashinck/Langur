package org.skylark.langur.application.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PlanResult {
    private String agentId;
    private int currentStepIndex;
    private List<PlanStepResult> steps;
}
