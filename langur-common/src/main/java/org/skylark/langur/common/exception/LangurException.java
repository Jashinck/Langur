package org.skylark.langur.common.exception;

public class LangurException extends RuntimeException {

    public LangurException(String message) {
        super(message);
    }

    public LangurException(String message, Throwable cause) {
        super(message, cause);
    }
}
