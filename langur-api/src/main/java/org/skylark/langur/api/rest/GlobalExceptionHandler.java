package org.skylark.langur.api.rest;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.api.dto.Result;
import org.skylark.langur.common.exception.AgentNotFoundException;
import org.skylark.langur.common.exception.LangurException;
import org.skylark.langur.common.exception.PlanNotFoundException;
import org.skylark.langur.common.exception.TaskNotFoundException;
import org.skylark.langur.common.exception.ToolNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器（对齐架构设计 13.3：统一 Result&lt;T&gt; + 语义化 HTTP 状态码）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({AgentNotFoundException.class, PlanNotFoundException.class, ToolNotFoundException.class, TaskNotFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(LangurException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleConflict(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler(LangurException.class)
    public ResponseEntity<Result<Void>> handleLangur(LangurException e) {
        log.warn("Business exception: {}", e.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "BUSINESS_ERROR", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}
