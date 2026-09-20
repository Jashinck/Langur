package org.skylark.langur.common.exception;

public class TaskNotFoundException extends LangurException {

    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
    }
}
