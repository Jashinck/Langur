package org.skylark.langur.infrastructure.harness.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.execution.HeuristicPlanner;
import org.skylark.langur.domain.harness.execution.Planner;
import org.skylark.langur.domain.model.agent.Agent;
import org.skylark.langur.domain.model.plan.PlanStep;
import org.skylark.langur.domain.model.plan.StepStatus;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 规划器（H3，M5）- 经 {@link LlmGateway} 走 REASONING 角色把目标拆解为有序 {@link PlanStep}。
 * <p>{@code langur.planner.type=llm} 时装配（否则用领域默认 {@link HeuristicPlanner}）。
 * 要求模型仅输出 JSON 数组 {@code [{"thought":"..","action":".."}]}；解析失败/调用异常/空结果
 * 一律降级 {@link HeuristicPlanner}，保证规划恒可用（P10）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.planner.type", havingValue = "llm")
public class LlmPlanner implements Planner {

    private static final String SYSTEM_PROMPT =
            "你是任务规划器。把用户目标拆解为有序、可独立执行的子步骤。"
                    + "仅输出 JSON 数组，元素形如 {\"thought\":\"子目标描述\",\"action\":\"react\"}，"
                    + "不要输出解释性文字或代码块标记。";

    private final LlmGateway gateway;
    private final ObjectMapper mapper;
    private final HeuristicPlanner fallback = new HeuristicPlanner();

    public LlmPlanner(LlmGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @Override
    public List<PlanStep> plan(String goal, Agent agent) {
        return planWithPrompt(goal, SYSTEM_PROMPT, agent);
    }

    @Override
    public List<PlanStep> replan(String goal, Agent agent, List<PlanStep> executed,
                                 PlanStep failedStep, String failureReason) {
        String prompt = SYSTEM_PROMPT
                + "\n原目标：" + safe(goal)
                + "\n失败步骤：" + (failedStep != null ? safe(failedStep.getThought()) : "")
                + "\n失败原因：" + safe(failureReason)
                + "\n请针对失败原因给出修订后的后续子步骤。";
        return planWithPrompt(goal, prompt, agent);
    }

    private List<PlanStep> planWithPrompt(String goal, String systemPrompt, Agent agent) {
        try {
            String raw = gateway.complete(ModelRole.REASONING, systemPrompt, safe(goal));
            List<PlanStep> steps = parse(raw);
            if (steps.isEmpty()) {
                log.warn("[E] LLM planner produced no steps, degrade to heuristic");
                return fallback.plan(goal, agent);
            }
            return steps;
        } catch (RuntimeException e) {
            log.warn("[E] LLM planning failed, degrade to heuristic: {}", e.getMessage());
            return fallback.plan(goal, agent);
        }
    }

    /** 解析模型输出中的 JSON 数组为步骤；容忍代码块围栏与前后缀噪声。包可见以便单测。 */
    List<PlanStep> parse(String raw) {
        List<PlanStep> steps = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return steps;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return steps;
        }
        try {
            JsonNode array = mapper.readTree(raw.substring(start, end + 1));
            if (!array.isArray()) {
                return steps;
            }
            int index = 0;
            for (JsonNode node : array) {
                String thought = node.path("thought").asText(node.path("step").asText(""));
                if (thought == null || thought.isBlank()) {
                    continue;
                }
                String action = node.path("action").asText("react");
                steps.add(PlanStep.builder()
                        .index(index++)
                        .thought(thought.trim())
                        .action(action.isBlank() ? "react" : action)
                        .status(StepStatus.PENDING)
                        .build());
            }
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("[E] failed to parse planner JSON, degrade to heuristic: {}", e.getMessage());
            return new ArrayList<>();
        }
        return steps;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
