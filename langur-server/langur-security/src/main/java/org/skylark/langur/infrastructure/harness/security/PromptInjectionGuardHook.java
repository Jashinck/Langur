package org.skylark.langur.infrastructure.harness.security;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
import org.skylark.langur.domain.harness.security.InjectionFinding;
import org.skylark.langur.domain.harness.security.PromptInjectionDetector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * H10 输入安全 - BEFORE_INFERENCE Prompt 注入防护钩子。
 * <p>对推理前置载荷（用户输入/装配上下文）跑注入检测规则引擎，命中应阻断类别即 ABORT，
 * 由 ReAct 循环记 interceptions 并落 INFERENCE_REJECTED 审计（→ H5 P0 告警）。</p>
 * <p>配置驱动（P9）：{@code langur.security.prompt-injection.enabled}，缺省启用。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.security.prompt-injection.enabled", havingValue = "true", matchIfMissing = true)
public class PromptInjectionGuardHook implements LifecycleHook {

    private final PromptInjectionDetector detector = new PromptInjectionDetector();

    @Override
    public HookPoint point() {
        return HookPoint.BEFORE_INFERENCE;
    }

    @Override
    public HookResult execute(HookContext context) {
        Optional<InjectionFinding> finding = detector.detect(context.getPayload());
        if (finding.isPresent() && finding.get().shouldBlock()) {
            InjectionFinding f = finding.get();
            log.warn("[H10] prompt injection blocked at BEFORE_INFERENCE: category={} risk={} trace={}",
                    f.getCategory(), f.getRisk(), context.getTraceId());
            return HookResult.abort("Prompt injection detected: " + f.getCategory());
        }
        return HookResult.continueFlow();
    }

    @Override
    public int order() {
        return 10;
    }
}
