package org.skylark.langur.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.application.assembler.AgentAssembler;
import org.skylark.langur.application.command.CreateAgentCommand;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.dto.AgentResult;
import org.skylark.langur.application.service.AgentApplicationService;
import org.skylark.langur.application.service.ToolRegistryService;
import org.skylark.langur.domain.model.message.MessagePartType;
import org.skylark.langur.domain.port.LLMPort;
import org.skylark.langur.domain.port.ToolProvider;
import org.skylark.langur.domain.repository.AgentRepository;
import org.skylark.langur.domain.repository.PlanRepository;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.domain.service.PlanningDomainService;
import org.skylark.langur.infrastructure.persistence.InMemoryAgentRepository;
import org.skylark.langur.infrastructure.persistence.InMemoryPlanRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentApplicationServiceTest {

    private AgentApplicationService agentApplicationService;
    private AgentRepository agentRepository;
    private LLMPort llmPort;

    @BeforeEach
    void setUp() {
        agentRepository = new InMemoryAgentRepository();
        llmPort = mock(LLMPort.class);
        AgentDomainService agentDomainService = new AgentDomainService(llmPort);
        PlanningDomainService planningDomainService = new PlanningDomainService();
        PlanRepository planRepository = new InMemoryPlanRepository();
        ToolProvider toolProvider = mock(ToolProvider.class);
        org.mockito.Mockito.when(toolProvider.getTools()).thenReturn(List.of());
        ToolRegistryService toolRegistryService = new ToolRegistryService(List.of(toolProvider));
        AgentAssembler assembler = new AgentAssembler();
        agentApplicationService = new AgentApplicationService(
                agentRepository, agentDomainService, planningDomainService,
                toolRegistryService, assembler, planRepository);
    }

    @Test
    void shouldCreateAgent() {
        CreateAgentCommand command = CreateAgentCommand.builder()
                .name("TestAgent")
                .description("A test agent")
                .model("gpt-4o")
                .temperature(0.7)
                .maxIterations(5)
                .build();

        AgentResult result = agentApplicationService.createAgent(command);

        assertNotNull(result.getId());
        assertEquals("TestAgent", result.getName());
        assertEquals("IDLE", result.getStatus());
    }

    @Test
    void shouldListAgents() {
        agentApplicationService.createAgent(CreateAgentCommand.builder()
                .name("Agent1").build());
        agentApplicationService.createAgent(CreateAgentCommand.builder()
                .name("Agent2").build());

        List<AgentResult> agents = agentApplicationService.listAgents();
        assertEquals(2, agents.size());
    }

    @Test
    void shouldDeleteAgent() {
        AgentResult created = agentApplicationService.createAgent(
                CreateAgentCommand.builder().name("ToDelete").build());

        agentApplicationService.deleteAgent(created.getId());

        assertThrows(org.skylark.langur.common.exception.AgentNotFoundException.class,
                () -> agentApplicationService.getAgent(created.getId()));
    }

    @Test
    void shouldRunWithStructuredMessagePartsWhenUserMessageMissing() {
        AgentResult created = agentApplicationService.createAgent(
                CreateAgentCommand.builder().name("Runner").build());
        org.mockito.Mockito.when(llmPort.decide(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(LLMPort.LLMDecision.finalAnswer("done"));

        AgentResult result = agentApplicationService.runAgent(
                RunAgentCommand.builder()
                        .agentId(created.getId())
                        .messageParts(List.of(
                                MessagePartInput.builder().type(MessagePartType.TEXT).content("你好").build(),
                                MessagePartInput.builder().type(MessagePartType.IMAGE).mediaUrl("https://img").build()))
                        .build());

        assertEquals("COMPLETED", result.getStatus());
        assertTrue(agentApplicationService.getLatestPlan(created.getId()).isPresent());
    }
}
