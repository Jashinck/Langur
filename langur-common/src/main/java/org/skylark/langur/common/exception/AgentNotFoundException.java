package org.skylark.langur.common.exception;

public class AgentNotFoundException extends LangurException {

    public AgentNotFoundException(String agentId) {
        super("Agent not found: " + agentId);
    }
}
