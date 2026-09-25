package org.skylark.langur.infrastructure.tool;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.context.vector.VectorMemoryService;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库工具提供者（H2 接通）- 当 {@link VectorMemoryService} 已装配时，暴露
 * {@code knowledge_retrieve} / {@code knowledge_ingest} 两个本地工具；未装配时返回空列表（P10 优雅降级）。
 * <p>经 {@code LocalToolRegistrar} 自动注册为 LOCAL 工具，纳入 T 组件四层校验链，无需改动调度器。</p>
 */
@Slf4j
@Component
public class KnowledgeToolProvider implements ToolProvider {

    private final ObjectProvider<VectorMemoryService> vectorMemoryServiceProvider;

    public KnowledgeToolProvider(ObjectProvider<VectorMemoryService> vectorMemoryServiceProvider) {
        this.vectorMemoryServiceProvider = vectorMemoryServiceProvider;
    }

    @Override
    public List<Tool> getTools() {
        List<Tool> tools = new ArrayList<>();
        VectorMemoryService vectorMemoryService = vectorMemoryServiceProvider.getIfAvailable();
        if (vectorMemoryService == null) {
            log.info("[T] VectorMemoryService not available, knowledge tools idle");
            return tools;
        }
        tools.add(new KnowledgeRetrievalTool(vectorMemoryService));
        tools.add(new KnowledgeIngestTool(vectorMemoryService));
        return tools;
    }
}
