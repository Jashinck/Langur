package org.skylark.langur.infrastructure.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10c / H8 验收：{@link SkillExecutor} 顺序执行步骤并回派 ToolDispatcher（嵌套工具走四层校验链），
 * 占位符（input / 步骤输出 / JSON 字段 / 列表下标）正确解析；CONDITION 按表达式跳转；
 * H8 新增 LLM_CALL / LOOP（退出条件 + 次数上限）/ PARALLEL（并发汇聚）/ SUB_WORKFLOW（嵌套技能）/
 * SUB_AGENT（子 Agent 委派）；步数硬上限触发 {@link SkillExecutionException}。
 */
class SkillExecutorTest {

    /**
     * 桩调度器：echo 回显、json 返回固定 JSON、counter 自增、fail 恒失败、skill:* 模拟嵌套技能，
     * 并记录每次请求的工具 id 与参数（并发安全，供 PARALLEL 分支共享）。
     */
    private static final class StubDispatcher implements ToolDispatcher {
        private final List<Map<String, Object>> recorded =
                Collections.synchronizedList(new ArrayList<>());
        private final List<String> recordedIds =
                Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public ToolCallResult dispatch(ToolCallRequest request) {
            recorded.add(request.getArguments());
            recordedIds.add(request.getToolId());
            String toolId = request.getToolId();
            if ("tool:fail".equals(toolId)) {
                return ToolCallResult.failure("forced failure", 1);
            }
            if ("tool:json".equals(toolId)) {
                return ToolCallResult.success("{\"temp\":21,\"city\":\"SH\"}", 1);
            }
            if ("tool:counter".equals(toolId)) {
                return ToolCallResult.success(String.valueOf(counter.incrementAndGet()), 1);
            }
            if (toolId != null && toolId.startsWith("skill:")) {
                return ToolCallResult.success("sub:" + toolId, 1);
            }
            return ToolCallResult.success("echo:" + request.getArguments().get("v"), 1);
        }

        long countOf(String toolId) {
            return recordedIds.stream().filter(toolId::equals).count();
        }
    }

    /** 桩 LLM 接缝：回显角色与解析后的用户提示词。 */
    private static final class StubLlmPort implements SkillLlmPort {
        @Override
        public String complete(String modelRole, String systemPrompt, String userPrompt) {
            return "llm:" + modelRole + ":" + userPrompt;
        }
    }

    /** 桩子 Agent 接缝：回显 agentId 与指令。 */
    private static final class StubSubAgent implements SubAgentInvoker {
        @Override
        public String run(String agentId, String instruction, Map<String, Object> context) {
            return "agent:" + agentId + ":" + instruction;
        }
    }

    private SkillExecutor executor(ToolDispatcher dispatcher) {
        return new SkillExecutor(dispatcher, new SkillExpressionResolver(new ObjectMapper()));
    }

    private SkillExecutor executor(ToolDispatcher dispatcher, SkillLlmPort llmPort, SubAgentInvoker subAgent) {
        return new SkillExecutor(dispatcher, new SkillExpressionResolver(new ObjectMapper()),
                llmPort, subAgent, null);
    }

    private SkillSpec spec(List<SkillStep> steps) {
        return SkillSpec.builder().name("demo").description("d").steps(steps).build();
    }

    @Test
    void shouldExecuteToolCallsInSequenceAndPassOutputs() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("s1", "tool:echo", Map.of("v", "${input.name}")),
                SkillStep.toolCall("s2", "tool:echo", Map.of("v", "${s1}"))));

        String out = executor(dispatcher).execute(spec, Map.of("name", "bob"));

        assertEquals("echo:echo:bob", out);
        assertEquals("bob", dispatcher.recorded.get(0).get("v"));
        assertEquals("echo:bob", dispatcher.recorded.get(1).get("v"));
    }

    @Test
    void shouldResolveJsonFieldFromPriorStepOutput() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("fetch", "tool:json", Map.of()),
                SkillStep.toolCall("show", "tool:echo", Map.of("v", "${fetch.temp}"))));

        String out = executor(dispatcher).execute(spec, Map.of());

        assertEquals("echo:21", out);
    }

    @Test
    void shouldBranchOnConditionTrue() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("probe", "tool:echo", Map.of("v", "${input.val}")),
                SkillStep.condition("gate", "${probe} == 'echo:ok'", "good", "END"),
                SkillStep.toolCall("good", "tool:echo", Map.of("v", "good"))));

        String out = executor(dispatcher).execute(spec, Map.of("val", "ok"));

        assertEquals("echo:good", out);
        assertEquals(2, dispatcher.recorded.size());
    }

    @Test
    void shouldBranchOnConditionFalseAndEnd() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("probe", "tool:echo", Map.of("v", "${input.val}")),
                SkillStep.condition("gate", "${probe} == 'echo:ok'", "good", "END"),
                SkillStep.toolCall("good", "tool:echo", Map.of("v", "good"))));

        String out = executor(dispatcher).execute(spec, Map.of("val", "nope"));

        // 条件为假 → END，保留上一步输出，且 good 步骤不执行
        assertEquals("echo:nope", out);
        assertEquals(1, dispatcher.recorded.size());
        assertTrue(dispatcher.recorded.stream().noneMatch(a -> "good".equals(a.get("v"))));
    }

    @Test
    void shouldEndOnConditionEndTarget() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.condition("gate", "${input.stop} == 'yes'", "END", null),
                SkillStep.toolCall("never", "tool:echo", Map.of("v", "ran"))));

        String out = executor(dispatcher).execute(spec, Map.of("stop", "yes"));

        assertEquals("", out);
        assertTrue(dispatcher.recorded.isEmpty());
    }

    @Test
    void shouldFailWhenStepToolFails() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("boom", "tool:fail", Map.of())));

        SkillExecutionException ex = assertThrows(SkillExecutionException.class,
                () -> executor(dispatcher).execute(spec, Map.of()));
        assertTrue(ex.getMessage().contains("forced failure"));
    }

    // ---- H8: LLM_CALL ----

    @Test
    void shouldExecuteLlmCallWithResolvedPrompts() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("prep", "tool:echo", Map.of("v", "${input.topic}")),
                SkillStep.llmCall("think", "REASONING", "sys", "about ${prep}")));

        String out = executor(dispatcher, new StubLlmPort(), null).execute(spec, Map.of("topic", "ai"));

        assertEquals("llm:REASONING:about echo:ai", out);
    }

    @Test
    void shouldThrowWhenLlmCallHasNoBackend() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(SkillStep.llmCall("think", "ACTION", "sys", "hi")));

        SkillExecutionException ex = assertThrows(SkillExecutionException.class,
                () -> executor(dispatcher).execute(spec, Map.of()));
        assertTrue(ex.getMessage().contains("requires an LLM backend"));
    }

    // ---- H8: LOOP ----

    @Test
    void shouldCapLoopAtMaxIterations() {
        StubDispatcher dispatcher = new StubDispatcher();
        // 无退出条件 → 仅受次数上限约束
        SkillSpec spec = spec(List.of(
                SkillStep.loop("lp", null, 3,
                        List.of(SkillStep.toolCall("tick", "tool:counter", Map.of())))));

        String out = executor(dispatcher).execute(spec, Map.of());

        assertEquals("3", out);
        assertEquals(3, dispatcher.countOf("tool:counter"));
    }

    @Test
    void shouldExitLoopWhenConditionTurnsFalse() {
        StubDispatcher dispatcher = new StubDispatcher();
        // 上限 10 但条件在计数到 3 时转假 → 由退出条件而非上限终止
        SkillSpec spec = spec(List.of(
                SkillStep.loop("lp", "${tick} < 3", 10,
                        List.of(SkillStep.toolCall("tick", "tool:counter", Map.of())))));

        String out = executor(dispatcher).execute(spec, Map.of());

        assertEquals("3", out);
        assertEquals(3, dispatcher.countOf("tool:counter"));
    }

    @Test
    void shouldNotEnterLoopWhenConditionFalseFromStart() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.loop("lp", "${input.go} == 'yes'", 5,
                        List.of(SkillStep.toolCall("tick", "tool:counter", Map.of())))));

        String out = executor(dispatcher).execute(spec, Map.of("go", "no"));

        assertEquals("", out);
        assertEquals(0, dispatcher.countOf("tool:counter"));
    }

    // ---- H8: PARALLEL ----

    @Test
    void shouldRunParallelBranchesAndGatherInOrder() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.parallel("par", List.of(
                        List.of(SkillStep.toolCall("a", "tool:echo", Map.of("v", "A"))),
                        List.of(SkillStep.toolCall("b", "tool:echo", Map.of("v", "B"))))),
                SkillStep.toolCall("final", "tool:echo", Map.of("v", "${par.0}"))));

        String out = executor(dispatcher).execute(spec, Map.of());

        // 两分支均执行，汇聚结果按分支顺序可通过下标引用
        assertEquals(3, dispatcher.countOf("tool:echo")); // a + b + final
        assertEquals("echo:echo:A", out);
    }

    // ---- H8: SUB_WORKFLOW ----

    @Test
    void shouldDispatchSubWorkflowThroughDispatcher() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.subWorkflow("child", "child", Map.of("v", "${input.x}"))));

        String out = executor(dispatcher).execute(spec, Map.of("x", "1"));

        // 嵌套技能回派 ToolDispatcher 到 skill:child，保留四层校验链
        assertEquals("sub:skill:child", out);
        assertTrue(dispatcher.recordedIds.contains("skill:child"));
    }

    // ---- H8: SUB_AGENT ----

    @Test
    void shouldDelegateSubAgentWithResolvedInstruction() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(
                SkillStep.subAgent("sa", "researcher", "find ${input.q}")));

        String out = executor(dispatcher, null, new StubSubAgent()).execute(spec, Map.of("q", "docs"));

        assertEquals("agent:researcher:find docs", out);
    }

    @Test
    void shouldThrowWhenSubAgentHasNoInvoker() {
        StubDispatcher dispatcher = new StubDispatcher();
        SkillSpec spec = spec(List.of(SkillStep.subAgent("sa", "researcher", "go")));

        SkillExecutionException ex = assertThrows(SkillExecutionException.class,
                () -> executor(dispatcher).execute(spec, Map.of()));
        assertTrue(ex.getMessage().contains("requires a sub-agent invoker"));
    }

    // ---- H8: 全局步数硬上限（终止闸门）----

    @Test
    void shouldTripStepLimitOnRunawayConditionLoop() {
        StubDispatcher dispatcher = new StubDispatcher();
        // 条件恒真跳回第一步 → 构造死循环，应被全局步数硬上限拦截
        SkillSpec spec = spec(List.of(
                SkillStep.toolCall("a", "tool:echo", Map.of("v", "x")),
                SkillStep.condition("spin", "true", "a", null)));

        SkillExecutionException ex = assertThrows(SkillExecutionException.class,
                () -> executor(dispatcher).execute(spec, Map.of()));
        assertTrue(ex.getMessage().contains("step limit exceeded"));
    }
}
