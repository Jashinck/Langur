package org.skylark.langur.infrastructure.tool;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.model.tool.ToolResult;
import org.skylark.langur.infrastructure.harness.context.vector.InMemoryVectorStore;
import org.skylark.langur.infrastructure.harness.context.vector.LexicalEmbeddingPort;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B（知识库接通）验收 - knowledge_ingest 写入、knowledge_retrieve 语义召回经工具接口生效；
 * 缺参/无命中优雅返回；{@link KnowledgeToolProvider} 在向量服务缺失时降级为空工具列表（P10）。
 * 全程离线：LexicalEmbeddingPort + InMemoryVectorStore。
 */
class KnowledgeToolTest {

    private final VectorMemoryService vectorMemoryService =
            new VectorMemoryService(new LexicalEmbeddingPort(), new InMemoryVectorStore());

    @Test
    void shouldIngestThenRetrieveKnowledge() {
        KnowledgeIngestTool ingest = new KnowledgeIngestTool(vectorMemoryService);
        KnowledgeRetrievalTool retrieve = new KnowledgeRetrievalTool(vectorMemoryService);

        ToolResult ingested = ingest.execute(Map.of(
                "namespace", "legal-contracts",
                "id", "clause-1",
                "content", "合同违约金上限不得超过标的额的百分之三十"));
        assertTrue(ingested.isSuccess());
        assertTrue(ingested.getContent().contains("ingested id=clause-1"));

        ToolResult found = retrieve.execute(Map.of(
                "namespace", "legal-contracts",
                "query", "违约金上限 百分之三十",
                "topK", 3));
        assertTrue(found.isSuccess());
        assertTrue(found.getContent().contains("违约金"), "召回内容应包含知识片段");
        assertTrue(found.getContent().contains("score="), "召回结果应标注相似度");
    }

    @Test
    void shouldReturnNoMatchMessageWhenNamespaceEmpty() {
        KnowledgeRetrievalTool retrieve = new KnowledgeRetrievalTool(vectorMemoryService);
        ToolResult result = retrieve.execute(Map.of("namespace", "empty-ns", "query", "anything"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("no matching knowledge"));
    }

    @Test
    void shouldFailWhenRequiredParamMissing() {
        assertFalse(new KnowledgeRetrievalTool(vectorMemoryService).execute(new HashMap<>()).isSuccess());
        assertFalse(new KnowledgeIngestTool(vectorMemoryService).execute(Map.of("namespace", "x")).isSuccess());
    }

    @Test
    void shouldExposeToolsOnlyWhenVectorServiceAvailable() {
        List<Tool> withService = new KnowledgeToolProvider(staticProvider(vectorMemoryService)).getTools();
        assertEquals(2, withService.size());
        assertTrue(withService.stream().anyMatch(t -> t.getName().equals("knowledge_retrieve")));
        assertTrue(withService.stream().anyMatch(t -> t.getName().equals("knowledge_ingest")));

        List<Tool> withoutService = new KnowledgeToolProvider(staticProvider(null)).getTools();
        assertTrue(withoutService.isEmpty(), "无向量服务时降级为空（P10）");
    }

    private ObjectProvider<VectorMemoryService> staticProvider(VectorMemoryService service) {
        return new ObjectProvider<>() {
            @Override
            public VectorMemoryService getObject() {
                return service;
            }

            @Override
            public VectorMemoryService getObject(Object... args) {
                return service;
            }

            @Override
            public VectorMemoryService getIfAvailable() {
                return service;
            }

            @Override
            public VectorMemoryService getIfUnique() {
                return service;
            }
        };
    }
}
