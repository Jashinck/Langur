package org.skylark.langur.infrastructure.tool;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolDefinition;
import org.skylark.langur.domain.model.tool.ToolResult;

import java.util.Map;

/**
 * 知识库摄取工具（H2 接通）- 以 {@code knowledge_ingest} 把文本写入 {@link VectorMemoryService}（向量化 UPSERT），
 * 使运行期可向领域知识库补充语料（如合同条款、尽调结论、法规摘录）。
 * <p>入参：content（必填）、namespace（缺省 {@value KnowledgeRetrievalTool#DEFAULT_NAMESPACE}）、
 * id（缺省自动生成）。二进制文档解析（PDF/DOCX）不在本工具职责内，应由上游解析后以文本投喂。</p>
 */
@Slf4j
public class KnowledgeIngestTool extends Tool {

    private static final ToolDefinition DEFINITION = ToolDefinition.of(
            "knowledge_ingest",
            "Ingest a text snippet into the domain knowledge base (vectorized upsert)",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "content", Map.of("type", "string", "description", "The text to ingest"),
                            "namespace", Map.of("type", "string", "description", "Knowledge namespace"),
                            "id", Map.of("type", "string", "description", "Stable id for upsert (optional)")
                    ),
                    "required", new String[]{"content"}
            )
    );

    private final VectorMemoryService vectorMemoryService;

    public KnowledgeIngestTool(VectorMemoryService vectorMemoryService) {
        super(DEFINITION);
        this.vectorMemoryService = vectorMemoryService;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            String content = parameters.get("content") == null ? null : String.valueOf(parameters.get("content"));
            if (content == null || content.isBlank()) {
                return ToolResult.failure("content is required");
            }
            String namespace = parameters.get("namespace") != null
                    ? String.valueOf(parameters.get("namespace")) : KnowledgeRetrievalTool.DEFAULT_NAMESPACE;
            String id = parameters.get("id") != null
                    ? String.valueOf(parameters.get("id"))
                    : namespace + "-" + java.util.UUID.randomUUID();
            vectorMemoryService.remember(namespace, id, content, Map.of("source", "knowledge_ingest"));
            return ToolResult.success("ingested id=" + id + " namespace=" + namespace
                    + " chars=" + content.length());
        } catch (Exception e) {
            log.error("knowledge_ingest failed", e);
            return ToolResult.failure(e.getMessage());
        }
    }
}
