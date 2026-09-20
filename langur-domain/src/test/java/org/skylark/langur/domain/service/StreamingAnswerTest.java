package org.skylark.langur.domain.service;

import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.model.tool.Tool;
import org.skylark.langur.domain.port.LLMPort;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T8 验收（领域）- 流式产出：默认 streamComplete 降级为一次性产出，token 被逐块回调并累积落库。
 */
class StreamingAnswerTest {

    @Test
    void streamFinalAnswerShouldAccumulateAndComplete() {
        LLMPort port = new LLMPort() {
            @Override
            public LLMDecision decide(String systemPrompt, String model,
                                      List<Map<String, String>> history, List<Tool> tools) {
                return LLMDecision.finalAnswer("ignored");
            }

            @Override
            public String complete(String systemPrompt, String model, String userMessage) {
                return "hello world";
            }
        };

        Agent agent = Agent.create(AgentConfig.defaultConfig("StreamAgent"));
        agent.addUserMessage("hi");

        AgentDomainService service = new AgentDomainService(port);
        StringBuilder received = new StringBuilder();
        String answer = service.streamFinalAnswer(agent, received::append);

        assertEquals("hello world", answer);
        assertEquals("hello world", received.toString());
        assertEquals(AgentStatus.COMPLETED, agent.getStatus());
    }
}
