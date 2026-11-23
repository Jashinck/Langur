package org.skylark.langur.common.exception;

public class ToolNotFoundException extends LangurException {

    public ToolNotFoundException(String toolName) {
        super("Tool not found: " + toolName);
    }
}
