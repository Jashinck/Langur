package org.skylark.langur.infrastructure.harness.tool;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.Checksums;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;
import org.skylark.langur.domain.harness.evaluation.AuditRecord;
import org.skylark.langur.domain.harness.evaluation.Checksums;
import org.skylark.langur.domain.harness.evaluation.EvaluationService;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionSpan;
import org.skylark.langur.domain.harness.evaluation.tracing.ExecutionTracer;
import org.skylark.langur.domain.harness.evaluation.tracing.SpanType;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDefinitionEntity;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;
import org.skylark.langur.domain.harness.tool.ToolRegistry;
import org.skylark.langur.domain.harness.tool.ToolValidator;
import org.skylark.langur.domain.harness.tool.ValidationResult;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.domain.port.ToolProvider;
import org.skylark.langur.infrastructure.harness.tool.rest.RestApiToolGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * T 组件 - 工具统一路由调度器默认实现。
 * <p>流程：注册中心准入 → 四层校验链 → 按 toolSource 路由 → 沙箱化执行（超时硬约束）。
 * LOCAL/SPI 复用既有 {@link Tool} 执行器；MCP/REST_API/SKILL 为后续阶段的扩展路由点。</p>
 */
@Slf4j
@Component
public class DefaultToolDispatcher implements ToolDispatcher {

    private final ToolRegistry toolRegistry;
    private final List<ToolValidator> validators;
    private final List<ToolProvider> toolProviders;
    /** REST_API 路由网关（T10a）；可选注入，未装配时 REST_API 源返回未启用。 */
    private RestApiToolGateway restApiToolGateway;
    /** 全链路追踪器（T12）；可选注入，缺省 NOOP，产生 TOOL Span。 */
    private ExecutionTracer tracer;
    /** 评估观测服务（T12）；可选注入，缺省不审计，产生工具调用不可篡改审计。 */
    private EvaluationService evaluationService;
    private final ExecutorService sandboxExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tool-sandbox");
        thread.setDaemon(true);
        return thread;
    });

    public DefaultToolDispatcher(ToolRegistry toolRegistry,
                                 List<ToolValidator> validators,
                                 List<ToolProvider> toolProviders) {
        this.toolRegistry = toolRegistry;
        this.validators = validators;
        this.toolProviders = toolProviders;
    }

    @Autowired(required = false)
    public void setRestApiToolGateway(RestApiToolGateway restApiToolGateway) {
        this.restApiToolGateway = restApiToolGateway;
    }

    @Autowired(required = false)
    public void setTracer(ExecutionTracer tracer) {
        this.tracer = tracer;
    }

    @Autowired(required = false)
    public void setEvaluationService(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @Override
    public ToolCallResult dispatch(ToolCallRequest request) {
        long start = System.currentTimeMillis();

        Optional<ToolDefinitionEntity> definitionOpt = toolRegistry.find(request.getToolId());
        if (definitionOpt.isEmpty()) {
            return ToolCallResult.failure("Tool not registered (whitelist): " + request.getToolId(), elapsed(start));
        }
        ToolDefinitionEntity definition = definitionOpt.get();

        for (ToolValidator validator : sortedValidators()) {
            ValidationResult result = validator.validate(request, definition);
            if (!result.isPassed()) {
                log.warn("[T] tool call rejected by validator: {}", result.getReason());
                return ToolCallResult.failure("Rejected: " + result.getReason(), elapsed(start));
            }
        }

        return observe(request, definition, start, () -> switch (definition.getSource()) {
            case LOCAL, SPI -> executeLocal(request, definition, start);
            case REST_API -> executeRestApi(request, definition, start);
            case MCP, SKILL -> ToolCallResult.failure(
                    "Tool source [" + definition.getSource() + "] routing not yet enabled", elapsed(start));
        });
    }

    /**
     * 包裹工具执行的 TOOL Span + 不可篡改审计（T12）：
     * 记录 toolId/source/success/elapsed，并以 SHA-256 checksum 落审计（失败静默降级，P10）。
     */
    private ToolCallResult observe(ToolCallRequest request, ToolDefinitionEntity definition,
                                   long start, java.util.function.Supplier<ToolCallResult> invocation) {
        try (ExecutionSpan span = (tracer != null ? tracer : ExecutionTracer.NOOP)
                .startSpan(SpanType.TOOL, "tool.invoke")) {
            span.setAttribute("toolId", request.getToolId());
            span.setAttribute("source", definition.getSource().name());
            ToolCallResult result = invocation.get();
            span.setAttribute("success", result.isSuccess());
            span.setAttribute("elapsedMillis", elapsed(start));
            auditInvocation(request, result);
            return result;
        }
    }

    private void auditInvocation(ToolCallRequest request, ToolCallResult result) {
        if (evaluationService == null) {
            return;
        }
        try {
            String detail = "tool=" + request.getToolId() + " success=" + result.isSuccess();
            String checksum = Checksums.sha256(request.getTraceId(), request.getCaller(),
                    request.getToolId(), String.valueOf(result.isSuccess()));
            evaluationService.audit(AuditRecord.of(request.getTraceId(), request.getCaller(),
                    "TOOL_INVOCATION", detail, checksum));
        } catch (RuntimeException e) {
            log.debug("Tool invocation audit failed, silently dropped", e);
        }
    }

    private ToolCallResult executeRestApi(ToolCallRequest request, ToolDefinitionEntity definition, long start) {
        String toolId = definition.getToolId();
        if (restApiToolGateway == null || !restApiToolGateway.supports(toolId)) {
            return ToolCallResult.failure("REST API gateway not available for tool: " + toolId, elapsed(start));
        }
        long timeoutMillis = definition.getTimeout() != null ? definition.getTimeout().toMillis() : 30_000L;
        try {
            String body = runGeneric(() -> restApiToolGateway.execute(toolId, request.getArguments()), timeoutMillis);
            return ToolCallResult.success(body, elapsed(start));
        } catch (TimeoutException e) {
            return ToolCallResult.failure("REST API tool timeout: " + toolId, elapsed(start));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolCallResult.failure("REST API tool error: " + cause.getMessage(), elapsed(start));
        }
    }

    private ToolCallResult executeLocal(ToolCallRequest request, ToolDefinitionEntity definition, long start) {
        Tool tool = findExecutableTool(definition.getToolId()).orElse(null);
        if (tool == null) {
            return ToolCallResult.failure("No executable tool bound: " + definition.getToolId(), elapsed(start));
        }
        try {
            ToolResult result = runInSandbox(
                    () -> tool.execute(request.getArguments()),
                    definition.getTimeout().toMillis());
            return result.isSuccess()
                    ? ToolCallResult.success(result.getContent(), elapsed(start))
                    : ToolCallResult.failure(result.getError(), elapsed(start));
        } catch (TimeoutException e) {
            return ToolCallResult.failure("Tool execution timeout: " + definition.getToolId(), elapsed(start));
        } catch (Exception e) {
            return ToolCallResult.failure("Tool execution error: " + e.getMessage(), elapsed(start));
        }
    }

    private ToolResult runInSandbox(Callable<ToolResult> callable, long timeoutMillis) throws Exception {
        return runGeneric(callable, timeoutMillis);
    }

    private <T> T runGeneric(Callable<T> callable, long timeoutMillis) throws Exception {
        Future<T> future = sandboxExecutor.submit(callable);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private Optional<Tool> findExecutableTool(String toolId) {
        return toolProviders.stream()
                .flatMap(provider -> provider.getTools().stream())
                .filter(tool -> tool.getName().equals(toolId))
                .findFirst();
    }

    private List<ToolValidator> sortedValidators() {
        return validators.stream()
                .sorted(Comparator.comparingInt(ToolValidator::order))
                .toList();
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
