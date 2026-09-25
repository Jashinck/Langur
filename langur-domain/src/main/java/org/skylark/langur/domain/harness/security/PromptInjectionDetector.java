package org.skylark.langur.domain.harness.security;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * H10 输入安全 - Prompt 注入检测规则引擎（纯领域，零外部依赖）。
 * <p>基于中英文常见注入话术做正则匹配，命中即返回 {@link InjectionFinding}。
 * 定位为轻量第一道防线；可选的 M5 语义分类由基础设施层在此之上叠加（P10 降级）。</p>
 */
public class PromptInjectionDetector {

    /** 角色劫持：伪装系统/开发者角色覆盖既有指令。 */
    private static final Pattern ROLE_HIJACK = Pattern.compile(
            "(?i)(you\\s+are\\s+now|act\\s+as\\s+(the\\s+)?system|pretend\\s+to\\s+be|"
                    + "from\\s+now\\s+on\\s+you|新的?身份|你现在是|扮演系统|假装你是)");

    /** 指令覆盖：忽略/推翻先前指令。 */
    private static final Pattern INSTRUCTION_OVERRIDE = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)|"
                    + "disregard\\s+(the\\s+)?(system|previous)|forget\\s+(everything|all\\s+rules)|"
                    + "override\\s+(the\\s+)?system|忽略(之前|上面|以上|所有)的?(指令|提示|规则)|"
                    + "无视(系统|之前)|忘记(所有|一切)(指令|规则)|推翻(系统|先前))");

    /** 系统提示词泄露：诱导吐露 system prompt / 隐藏指令。 */
    private static final Pattern SYSTEM_PROMPT_LEAK = Pattern.compile(
            "(?i)(reveal\\s+(your\\s+)?(system\\s+)?prompt|print\\s+(your\\s+)?instructions|"
                    + "show\\s+(me\\s+)?(your\\s+)?(system|hidden)\\s+(prompt|instructions)|"
                    + "what\\s+(are|is)\\s+your\\s+(system\\s+)?(prompt|instructions)|"
                    + "泄露|吐出|显示你的?(系统)?提示词|告诉我你的(系统)?指令|你的隐藏指令)");

    /** 越权/护栏绕过：诱导突破限制、进入开发者模式等。 */
    private static final Pattern GUARDRAIL_BYPASS = Pattern.compile(
            "(?i)(jailbreak|developer\\s+mode|do\\s+anything\\s+now|\\bdan\\b\\s+mode|"
                    + "bypass\\s+(the\\s+)?(filter|safety|restriction)|disable\\s+(your\\s+)?(safety|filter)|"
                    + "越狱|开发者模式|绕过(安全|限制|过滤)|解除(限制|封印))");

    private static final List<Rule> RULES = List.of(
            new Rule("INSTRUCTION_OVERRIDE", INSTRUCTION_OVERRIDE, InjectionFinding.InjectionRisk.HIGH),
            new Rule("ROLE_HIJACK", ROLE_HIJACK, InjectionFinding.InjectionRisk.HIGH),
            new Rule("GUARDRAIL_BYPASS", GUARDRAIL_BYPASS, InjectionFinding.InjectionRisk.HIGH),
            new Rule("SYSTEM_PROMPT_LEAK", SYSTEM_PROMPT_LEAK, InjectionFinding.InjectionRisk.MEDIUM));

    /**
     * 检测输入文本中的注入尝试。
     *
     * @return 首个命中的注入结果；无命中返回 {@link Optional#empty()}
     */
    public Optional<InjectionFinding> detect(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            var matcher = rule.pattern.matcher(input);
            if (matcher.find()) {
                return Optional.of(InjectionFinding.of(rule.category, matcher.group(), rule.risk));
            }
        }
        return Optional.empty();
    }

    private record Rule(String category, Pattern pattern, InjectionFinding.InjectionRisk risk) {
    }
}
