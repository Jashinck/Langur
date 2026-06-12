package org.skylark.langur.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.application.assembler.AgentAssembler;
import org.skylark.langur.application.command.CreateAgentCommand;
import org.skylark.langur.application.command.MessagePartInput;
import org.skylark.langur.application.command.RunAgentCommand;
import org.skylark.langur.application.service.AgentApplicationService;
import org.skylark.langur.application.service.ToolRegistryService;
import org.skylark.langur.domain.model.message.MessagePartType;
import org.skylark.langur.domain.repository.AgentRepository;
import org.skylark.langur.domain.repository.PlanRepository;
import org.skylark.langur.domain.service.AgentDomainService;
import org.skylark.langur.domain.service.PlanningDomainService;
import org.skylark.langur.infrastructure.llm.LLMPort;
import org.skylark.langur.infrastructure.persistence.InMemoryAgentRepository;
import org.skylark.langur.infrastructure.persistence.InMemoryPlanRepository;
import org.skylark.langur.infrastructure.tool.BuiltinToolRegistry;
import org.skylark.langur.interfaces.dto.AgentResponse;

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
        BuiltinToolRegistry builtinToolRegistry = mock(BuiltinToolRegistry.class);
        org.mockito.Mockito.when(builtinToolRegistry.getBuiltinTools()).thenReturn(List.of());
        org.springframework.web.reactive.function.client.WebClient.Builder builder =
                mock(org.springframework.web.reactive.function.client.WebClient.Builder.class);
        ToolRegistryService toolRegistryService = new ToolRegistryService(builtinToolRegistry);
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

        AgentResponse response = agentApplicationService.createAgent(command);

        assertNotNull(response.getId());
        assertEquals("TestAgent", response.getName());
        assertEquals("IDLE", response.getStatus());
    }

    @Test
    void shouldListAgents() {
        agentApplicationService.createAgent(CreateAgentCommand.builder()
                .name("Agent1").build());
        agentApplicationService.createAgent(CreateAgentCommand.builder()
                .name("Agent2").build());

        List<AgentResponse> agents = agentApplicationService.listAgents();
        assertEquals(2, agents.size());
    }

    @Test
    void shouldDeleteAgent() {
        AgentResponse created = agentApplicationService.createAgent(
                CreateAgentCommand.builder().name("ToDelete").build());

        agentApplicationService.deleteAgent(created.getId());

        assertThrows(IllegalArgumentException.class,
                () -> agentApplicationService.getAgent(created.getId()));
    }

    @Test
    void shouldRunWithStructuredMessagePartsWhenUserMessageMissing() {
        AgentResponse created = agentApplicationService.createAgent(
                CreateAgentCommand.builder().name("Runner").build());
        org.mockito.Mockito.when(llmPort.decide(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(LLMPort.LLMDecision.finalAnswer("done"));

        AgentResponse response = agentApplicationService.runAgent(
                RunAgentCommand.builder()
                        .agentId(created.getId())
                        .messageParts(List.of(
                                MessagePartInput.builder().type(MessagePartType.TEXT).content("你好").build(),
                                MessagePartInput.builder().type(MessagePartType.IMAGE).mediaUrl("https://img").build()))
                        .build());

        assertEquals("COMPLETED", response.getStatus());
        assertTrue(agentApplicationService.getLatestPlan(created.getId()).isPresent());
    }
}