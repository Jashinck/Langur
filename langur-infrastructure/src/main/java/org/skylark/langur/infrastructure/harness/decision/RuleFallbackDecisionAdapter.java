package org.skylark.langur.infrastructure.harness.decision;

import lombok.extern.slf4j.Slf4j;
import org.skylark.langur.domain.harness.decision.DecisionAnswer;
import org.skylark.langur.domain.harness.decision.DecisionQuestion;
import org.skylark.langur.domain.harness.decision.DecisionRequest;
import org.skylark.langur.domain.harness.decision.DecisionResponse;
import org.skylark.langur.domain.harness.decision.DecisionType;
import org.skylark.langur.domain.harness.security.OutputContentReviewer;
import org.skylark.langur.domain.port.DecisionPort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策平面规则/正则兜底适配器（J1）。无后端 / 关闭 / 后端不可用时提供离线、确定性的保守判定（P10）。
 * <p>复用 H10 {@link OutputContentReviewer} 语义对 {@code state} 做涉密/资损/合规扫描：命中→风险信号置 1.0。
 * <b>所有判定 {@code confidence=0.0}</b>——确保 J3 {@code ThresholdRouter} 对兜底结果一律 fail-closed
 * （回退既有规则 / 人审 / 中断，P12），绝不因兜底而放松安全闸门。CHOICE 无候选时返回 {@code null} 选中项。</p>
 * <p>本类不带 {@code @Component}：由 J3 {@code DecisionConfiguration} 装配为装饰链最内层兜底，
 * 亦被 {@link TypeSafeDecisionAdapter} 在后端异常/超时时委派。</p>
 */
@Slf4j
public class RuleFallbackDecisionAdapter implements DecisionPort {

    private final OutputContentReviewer reviewer = new OutputContentReviewer();

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        if (request == null || request.hasNoQuestions()) {
            return DecisionResponse.of(Map.of());
        }
        boolean risky = reviewer.review(request.state()).isPresent();
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        for (DecisionQuestion question : request.questions()) {
            answers.put(question.key(), ruleAnswer(question, risky));
        }
        return DecisionResponse.of(answers);
    }

    /** 保守规则判定：confidence 恒 0.0（交由 ThresholdRouter fail-closed）；PROBABILITY/SCORE 用风险扫描给值。 */
    private DecisionAnswer ruleAnswer(DecisionQuestion question, boolean risky) {
        if (question.type() == DecisionType.CHOICE) {
            String pick = question.criteria().isEmpty()
                    ? null
                    : question.criteria().keySet().iterator().next();
            return DecisionAnswer.ofChoice(pick, 0d, Map.of());
        }
        return new DecisionAnswer(question.type(), null, risky ? 1d : 0d, 0d, Map.of());
    }
}
