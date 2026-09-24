package org.skylark.langur.infrastructure.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.domain.harness.tool.ToolCallRequest;
import org.skylark.langur.domain.harness.tool.ToolCallResult;
import org.skylark.langur.domain.harness.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10c 验收：{@link SkillExecutor} 顺序执行 TOOL_CALL 步骤并回派 ToolDispatcher，
 * 占位符（input / 步骤输出 / JSON 字段）正确解析，CONDITION 按表达式结果跳转分支。
 */
class SkillExecutorTest {

    /** 桩调度器：echo:{v} 回显、json 返回固定 JSON、fail 恒失败，并记录每次请求参数。 */
    private static final class StubDispatcher implements ToolDispatcher {
        private final List<Map<String, Object>> recorded = new ArrayList<>();

        @Override
        public ToolCallResult dispatch(ToolCallRequest request) {
            recorded.add(request.getArguments());
            String toolId = request.getToolId();
            if ("tool:fail".equals(toolId)) {
                return ToolCallResult.failure("forced failure", 1);
            }
            if ("tool:json".equals(toolId)) {
                return ToolCallResult.success("{\"temp\":21,\"city\":\"SH\"}", 1);
            }
            return ToolCallResult.success("echo:" + request.getArguments().get("v"), 1);
        }
    }

    private SkillExecutor executor(ToolDispatcher dispatcher) {
        return new SkillExecutor(dispatcher, new SkillExpressionResolver(new ObjectMapper()));
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
}
