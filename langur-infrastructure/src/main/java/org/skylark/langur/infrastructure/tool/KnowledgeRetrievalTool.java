package org.skylark.langur.infrastructure.tool;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.harness.context.vector.VectorRecord;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（H2 接通）- 以 {@code knowledge_retrieve} 暴露 {@link VectorMemoryService} 语义召回，
 * 使 ReAct/Plan 能按需拉取领域知识（如法律合同知识库），经 T 组件四层校验链统一管控。
 * <p>入参：query（必填）、namespace（缺省 {@value #DEFAULT_NAMESPACE}）、topK（缺省 {@value #DEFAULT_TOP_K}）。
 * 出参：按相似度降序拼接的知识片段文本。</p>
 */
@Slf4j
public class KnowledgeRetrievalTool extends Tool {

    static final String DEFAULT_NAMESPACE = "knowledge";
    static final int DEFAULT_TOP_K = 5;

    private static final ToolDefinition DEFINITION = ToolDefinition.of(
            "knowledge_retrieve",
            "Retrieve the most relevant domain knowledge snippets for a query via semantic search",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "The natural-language query"),
                            "namespace", Map.of("type", "string",
                                    "description", "Knowledge namespace, e.g. legal-contracts"),
                            "topK", Map.of("type", "integer", "description", "Max snippets to return")
                    ),
                    "required", new String[]{"query"}
            )
    );

    private final VectorMemoryService vectorMemoryService;

    public KnowledgeRetrievalTool(VectorMemoryService vectorMemoryService) {
        super(DEFINITION);
        this.vectorMemoryService = vectorMemoryService;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            String query = str(parameters.get("query"));
            if (query == null || query.isBlank()) {
                return ToolResult.failure("query is required");
            }
            String namespace = parameters.get("namespace") != null
                    ? str(parameters.get("namespace")) : DEFAULT_NAMESPACE;
            int topK = intParam(parameters.get("topK"), DEFAULT_TOP_K);
            // tokenBudget<=0 表示不按预算截断，仅受 topK 限制
            List<VectorRecord> records = vectorMemoryService.recall(namespace, query, topK, 0L);
            if (records.isEmpty()) {
                return ToolResult.success("(no matching knowledge in namespace '" + namespace + "')");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < records.size(); i++) {
                VectorRecord record = records.get(i);
                sb.append('[').append(i + 1).append("] (score=")
                        .append(String.format("%.4f", record.getScore())).append(") ")
                        .append(record.getContent()).append('\n');
            }
            return ToolResult.success(sb.toString().trim());
        } catch (Exception e) {
            log.error("knowledge_retrieve failed", e);
            return ToolResult.failure(e.getMessage());
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intParam(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
