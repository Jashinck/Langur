package org.skylark.langur.infrastructure.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.agent.AgentConfig;
import org.skylark.langur.domain.model.agent.AgentId;
import org.skylark.langur.domain.model.agent.AgentStatus;
import org.skylark.langur.domain.model.execution.AgentRunTask;
import org.skylark.langur.domain.model.plan.Plan;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.domain.repository.AgentRepository;
import org.skylark.langur.domain.repository.AgentRunTaskRepository;
import org.skylark.langur.domain.repository.PlanRepository;
import org.skylark.langur.infrastructure.persistence.jpa.entity.AgentDO;
import org.skylark.langur.infrastructure.persistence.jpa.entity.AgentRunTaskDO;
import org.skylark.langur.infrastructure.persistence.jpa.entity.PlanDO;
import org.skylark.langur.infrastructure.persistence.jpa.impl.JpaAgentRepository;
import org.skylark.langur.infrastructure.persistence.jpa.impl.JpaAgentRunTaskRepository;
import org.skylark.langur.infrastructure.persistence.jpa.impl.JpaPlanRepository;
import org.skylark.langur.infrastructure.persistence.jpa.repository.AgentJpaRepository;
import org.skylark.langur.infrastructure.persistence.jpa.repository.AgentRunTaskJpaRepository;
import org.skylark.langur.infrastructure.persistence.jpa.repository.PlanJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "langur.repository.type=jpa"
})
@Import(JpaRepositoriesTest.TestConfig.class)
class JpaRepositoriesTest {

    @Autowired
    private AgentJpaRepository agentJpaRepository;
    @Autowired
    private AgentRunTaskJpaRepository taskJpaRepository;
    @Autowired
    private PlanJpaRepository planJpaRepository;
    @Autowired
    private JsonValueMapper jsonValueMapper;

    private AgentRepository agentRepository;
    private AgentRunTaskRepository taskRepository;
    private PlanRepository planRepository;

    @BeforeEach
    void setUp() {
        agentRepository = new JpaAgentRepository(agentJpaRepository, jsonValueMapper);
        taskRepository = new JpaAgentRunTaskRepository(taskJpaRepository);
        planRepository = new JpaPlanRepository(planJpaRepository, jsonValueMapper);
    }

    @Test
    void shouldPersistAndRestoreAgentWithConversationAndToolNames() {
        Agent agent = Agent.restore(
                AgentId.generate(),
                AgentConfig.builder()
                        .name("agent")
                        .description("desc")
                        .systemPrompt("sys")
                        .model("gpt-4o")
                        .temperature(0.3)
                        .maxIterations(7)
                        .maxTokens(2048)
                        .build(),
                AgentStatus.RUNNING,
                List.of("http-call"),
                List.of(Map.of("role", "user", "content", "hello")),
                2,
                "boom",
                Instant.now(),
                Instant.now());

        agentRepository.save(agent);
        Agent loaded = agentRepository.findById(agent.getId()).orElseThrow();

        assertEquals(agent.getConfig().getModel(), loaded.getConfig().getModel());
        assertEquals(List.of("http-call"), loaded.getRegisteredToolNames());
        assertEquals("hello", loaded.getConversationHistory().get(0).get("content"));
        assertEquals("boom", loaded.getLastError());
    }

    @Test
    void shouldPersistPlanAndTask() {
        Plan plan = new Plan("agent-1");
        plan.addStep(PlanStep.builder()
                .index(1)
                .thought("t")
                .action("call")
                .actionInput(Map.of("k", "v"))
                .observation("done")
                .status(StepStatus.COMPLETED)
                .build());
        planRepository.save(plan);

        AgentRunTask task = AgentRunTask.create("agent-1", "user", "tenant", "session");
        task.markRunning();
        task.markCompleted("summary");
        taskRepository.save(task);

        Plan loadedPlan = planRepository.findByAgentId("agent-1").orElseThrow();
        AgentRunTask loadedTask = taskRepository.findById(task.getTaskId()).orElseThrow();

        assertEquals(1, loadedPlan.getSteps().size());
        assertEquals("summary", loadedTask.getResultSummary());
        assertEquals(1, taskRepository.findByAgentId("agent-1").size());
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {AgentDO.class, AgentRunTaskDO.class, PlanDO.class})
    @EnableJpaRepositories(basePackageClasses = {AgentJpaRepository.class, AgentRunTaskJpaRepository.class, PlanJpaRepository.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JsonValueMapper jsonValueMapper(ObjectMapper objectMapper) {
            return new JsonValueMapper(objectMapper);
        }
    }
}
