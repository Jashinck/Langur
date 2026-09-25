package org.skylark.langur.domain.harness.security;

import lombok.Getter;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * H10 输出安全 - 内容审核规则引擎（纯领域，零外部依赖）。
 * <p>对最终答案做涉密 / 资损 / 合规三类风险扫描，命中即返回 {@link ContentViolation}，
 * 由 BEFORE_OUTPUT 钩子据此 ABORT 阻断并告警（对齐 RoadMap §H10）。</p>
 */
public class OutputContentReviewer {

    /** 涉密：内部密钥、令牌、私网凭证等敏感信息外泄。 */
    private static final Pattern CONFIDENTIAL = Pattern.compile(
            "(?i)(-----BEGIN\\s+[A-Z\\s]*PRIVATE KEY-----|api[_-]?key\\s*[:=]\\s*\\S+|"
                    + "secret\\s*[:=]\\s*\\S+|password\\s*[:=]\\s*\\S+|access[_-]?token\\s*[:=]\\s*\\S+|"
                    + "内部机密|绝密文件|密钥[:：]\\s*\\S+)");

    /** 资损：诱导转账、代付、退款到陌生账户等资金操作话术。 */
    private static final Pattern FINANCIAL_LOSS = Pattern.compile(
            "(?i)(transfer\\s+\\$?\\d+.*to\\s+account|wire\\s+the\\s+funds|"
                    + "转账\\d+元?到|汇款到.*账户|代付款|垫付.*款项|退款到.*陌生账户|"
                    + "立即付款到|将资金转入)");

    /** 合规：违法违规、歧视、暴力等禁止性内容。 */
    private static final Pattern COMPLIANCE = Pattern.compile(
            "(?i)(如何制造(炸弹|毒品|武器)|how\\s+to\\s+(make|build)\\s+(a\\s+)?(bomb|weapon|drug)|"
                    + "非法获取.*个人信息|绕过.*实名认证|洗钱|传销)");

    private static final List<Rule> RULES = List.of(
            new Rule("CONFIDENTIAL", CONFIDENTIAL),
            new Rule("FINANCIAL_LOSS", FINANCIAL_LOSS),
            new Rule("COMPLIANCE", COMPLIANCE));

    /**
     * 审核输出文本。
     *
     * @return 首个命中的违规；无命中返回 {@link Optional#empty()}
     */
    public Optional<ContentViolation> review(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            var matcher = rule.pattern.matcher(output);
            if (matcher.find()) {
                return Optional.of(ContentViolation.of(rule.category, matcher.group()));
            }
        }
        return Optional.empty();
    }

    private record Rule(String category, Pattern pattern) {
    }

    /** 内容审核命中结果。 */
    @Getter
    public static class ContentViolation {
        private final String category;
        private final String matched;

        private ContentViolation(String category, String matched) {
            this.category = category;
            this.matched = matched;
        }

        public static ContentViolation of(String category, String matched) {
            return new ContentViolation(category, matched);
        }
    }
}
