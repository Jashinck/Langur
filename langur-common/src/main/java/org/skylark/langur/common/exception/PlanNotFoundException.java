package org.skylark.langur.common.exception;

public class PlanNotFoundException extends LangurException {

    public PlanNotFoundException(String agentId) {
        super("Plan not found for agent: " + agentId);
    }
}
