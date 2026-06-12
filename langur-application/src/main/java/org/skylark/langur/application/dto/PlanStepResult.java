package org.skylark.langur.application.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class PlanStepResult {
    private int index;
    private String thought;
    private String action;
    private Map<String, Object> actionInput;
    private String observation;
    private String status;
}
