package org.skylark.langur.infrastructure.harness.rsi;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
import org.skylark.langur.domain.port.DecisionPort;
import org.skylark.langur.infrastructure.llm.LlmGateway;
import org.skylark.langur.infrastructure.llm.ModelRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * R1 反思自检（RSI L1，{@code ReflectionHook}）——挂 BEFORE_OUTPUT 的改写型钩子。
 * <p>对最终答案做<b>两级反思</b>（C3 增量）：① Jev 廉价初筛（决策平面 {@code score} 对质量评分，单次低成本往返）；
 * ② 仅当初筛选"低质或低置信"才升级 M5 自我批评（{@link LlmGateway} {@link ModelRole#REASONING}），
 * 命中可改写/剔除越界内容即 {@code MODIFY}，否则放行。高置信高质答案跳过 M5 以控成本（P10）。</p>
 * <p>安全红线（P11/P12）：{@code order()=5 <} H10 {@code ContentReviewOutputHook#order()=10}，使反思改写发生在
 * 内容审核<b>之前</b>——改写后的答案仍受 H10 涉密/资损/合规审核，反思不能绕过安全层；本钩子只 MODIFY 绝不 ABORT
 * （阻断交安全闸门）。任一下游（DecisionPort 缺失/异常、M5 失败）一律降级放行原始答案（P10）。</p>
 * <p>配置驱动（P9，{@code langur.rsi.reflection.*}）：与总开关 {@code langur.rsi.enabled} AND 生效、默认关（P11）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "langur.rsi.reflection.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ReflectionHook implements LifecycleHook {

    private static final String PRESCREEN_KEY = "answer-quality";
    private static final String DEFAULT_CRITIQUE_PROMPT =
            "你是一名严格但无偏的内部质检。以下是对用户请求的最终答复。\n"
                    + "请判断其完整性与安全性：是否清楚回答了任务目标、有无漫无目的的重复、是否包含不应公开的敏感或违禁内容。\n"
                    + "若答复质量足够高，仅输出原文，不要改动任何字。\n"
                    + "若发现问题，重写为更完整、精炼、安全的版本；若问题无法在不改变事实的前提下修复，仍输出原文。\n"
                    + "只输出最终答复本身，不要任何解释、前缀或引号。";

    /** 决策平面端口（J1，可选注入）；缺失/异常 → 跳过初筛直接升级 M5（P10）。 */
    private final ObjectProvider<DecisionPort> decisionPortProvider;

    /** 统一 LLM 网关（角色→模型 + 主备降级）；M5 反思经此调用。 */
    private final LlmGateway llmGateway;

    private final RsiProperties properties;

    public ReflectionHook(ObjectProvider<DecisionPort> decisionPortProvider,
                          LlmGateway llmGateway,
                          RsiProperties properties) {
        this.decisionPortProvider = decisionPortProvider;
        this.llmGateway = llmGateway;
        this.properties = properties;
    }

    @Override
    public HookPoint point() {
        return HookPoint.BEFORE_OUTPUT;
    }

    @Override
    public HookResult execute(HookContext context) {
        String draft = context.getPayload();
        if (draft == null || draft.isBlank()) {
            return HookResult.continueFlow();
        }
        // ① Jev 廉价初筛：质量与置信双达阈 → 已足够良好，跳过 M5 省钱（P10）；否则升级 M5 反思
        if (passesPreScreen(draft)) {
            return HookResult.continueFlow();
        }
        // ② M5 自我批评改写
        String improved = doReflect(draft);
        if (improved == null || improved.isBlank() || improved.equals(draft)) {
            return HookResult.continueFlow();
        }
        log.info("[R1] reflection rewrote output at BEFORE_OUTPUT: trace={} before={} after={}",
                context.getTraceId(), draft.length(), improved.length());
        return HookResult.modify(improved);
    }

    /** order < H10 内容审核(order=10)：反思改写必须先于安全审核，使改写后的答案仍被 H10 复查。 */
    @Override
    public int order() {
        return 5;
    }

    @Override
    public boolean enabled() {
        return properties != null && properties.isEnabled()
                && properties.getReflection() != null && properties.getReflection().isEnabled();
    }

    private boolean passesPreScreen(String draft) {
        DecisionPort port = decisionPortProvider.getIfAvailable();
        if (port == null) {
            return false;
        }
        DecisionAnswer quality;
        try {
            DecisionResponse response = port.decide(DecisionRequest.of(
                    "反思初筛: " + draft,
                    DecisionQuestion.score(PRESCREEN_KEY,
                            "对给定最终答复的质量评分（0-1，越高越完整、准确且无越界内容）")));
            quality = response.answer(PRESCREEN_KEY);
        } catch (RuntimeException e) {
            log.warn("[R1] decision pre-screen failed, escalating to M5: {}", e.getMessage());
            return false;
        }
        double threshold = properties.getReflection().getPrescreenThreshold();
        return quality != null
                && quality.type() == DecisionType.SCORE
                && quality.confidentAtLeast(threshold)
                && quality.valueAtLeast(threshold);
    }

    /** M5 自我批评（System-2）：单次补全；失败降级返回 {@code null}（调用方放行原始答案，P10）。 */
    private String doReflect(String draft) {
        try {
            String sys = properties.getReflection().getCritiqueSystemPrompt();
            if (sys == null || sys.isBlank()) {
                sys = DEFAULT_CRITIQUE_PROMPT;
            }
            return llmGateway.complete(ModelRole.REASONING, sys, draft);
        } catch (RuntimeException e) {
            log.warn("[R1] M5 reflection failed, degrade to original: {}", e.getMessage());
            return null;
        }
    }
}
