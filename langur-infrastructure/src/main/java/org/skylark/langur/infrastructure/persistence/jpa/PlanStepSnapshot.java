package org.skylark.langur.infrastructure.persistence.jpa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanStepSnapshot {
    private int index;
    private String thought;
    private String action;
    private Map<String, Object> actionInput;
    private String observation;
    private String status;
}
