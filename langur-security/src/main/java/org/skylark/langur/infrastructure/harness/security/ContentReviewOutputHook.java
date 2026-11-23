package org.skylark.langur.infrastructure.harness.security;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.lifecycle.HookContext;
import org.skylark.langur.domain.harness.lifecycle.HookPoint;
import org.skylark.langur.domain.harness.lifecycle.HookResult;
import org.skylark.langur.domain.harness.lifecycle.LifecycleHook;
import org.skylark.langur.domain.harness.security.OutputContentReviewer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * H10 输出安全 - BEFORE_OUTPUT 内容审核钩子。
 * <p>对最终答案跑涉密/资损/合规三类审核规则，命中即 ABORT，由 ReAct 循环终止任务
 * 并落 OUTPUT_REJECTED 审计（→ H5 告警）。</p>
 * <p>配置驱动（P9）：{@code langur.security.content-review.enabled}，缺省启用。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langur.security.content-review.enabled", havingValue = "true", matchIfMissing = true)
public class ContentReviewOutputHook implements LifecycleHook {

    private final OutputContentReviewer reviewer = new OutputContentReviewer();

    @Override
    public HookPoint point() {
        return HookPoint.BEFORE_OUTPUT;
    }

    @Override
    public HookResult execute(HookContext context) {
        Optional<OutputContentReviewer.ContentViolation> violation = reviewer.review(context.getPayload());
        if (violation.isPresent()) {
            OutputContentReviewer.ContentViolation v = violation.get();
            log.warn("[H10] output content rejected at BEFORE_OUTPUT: category={} trace={}",
                    v.getCategory(), context.getTraceId());
            return HookResult.abort("Output content rejected: " + v.getCategory());
        }
        return HookResult.continueFlow();
    }

    @Override
    public int order() {
        return 10;
    }
}
