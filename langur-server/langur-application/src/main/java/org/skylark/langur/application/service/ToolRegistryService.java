package org.skylark.langur.application.service;

import lombok.RequiredArgsConstructor;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.ToolProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    private final List<ToolProvider> toolProviders;
    private final Map<String, Tool> customTools = new ConcurrentHashMap<>();

    public void registerCustomTool(Tool tool) {
        customTools.put(tool.getName(), tool);
    }

    public List<Tool> getToolsByNames(List<String> names) {
        List<Tool> all = getAllTools();
        if (names == null || names.isEmpty()) return all;
        return all.stream().filter(t -> names.contains(t.getName())).toList();
    }

    public List<Tool> getAllTools() {
        List<Tool> all = new ArrayList<>();
        toolProviders.forEach(p -> all.addAll(p.getTools()));
        all.addAll(customTools.values());
        return all;
    }
}
