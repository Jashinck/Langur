package org.skylark.langur.application.assembler;

import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.domain.model.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentAssembler {

    public AgentResult toResult(Agent agent) {
        return AgentResult.builder()
                .id(agent.getId().getValue())
                .name(agent.getConfig().getName())
                .description(agent.getConfig().getDescription())
                .status(agent.getStatus().name())
                .model(agent.getConfig().getModel())
                .iterationCount(agent.getIterationCount())
                .toolCount(agent.getTools().size())
                .lastError(agent.getLastError())
                .answer(extractFinalAnswer(agent))
                .createdAt(agent.getCreatedAt().toString())
                .updatedAt(agent.getUpdatedAt().toString())
                .build();
    }

    /**
     * 提取最终答案：会话历史中最后一条 assistant 消息（markCompleted 时写入）。
     */
    private String extractFinalAnswer(Agent agent) {
        List<Map<String, String>> history = agent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> message = history.get(i);
            if ("assistant".equals(message.get("role"))) {
                return message.get("content");
            }
        }
        return null;
    }
}
