package org.skylark.langur.domain.harness.security;

import lombok.Getter;

/**
 * 注入检测命中结果 - 承载命中的规则类别、匹配片段与风险等级。
 */
@Getter
public class InjectionFinding {

    /** 注入类别（如 ROLE_HIJACK / INSTRUCTION_OVERRIDE / SYSTEM_PROMPT_LEAK）。 */
    private final String category;
    /** 命中的原始片段（用于审计溯源，不返回完整载荷以防二次注入）。 */
    private final String matched;
    /** 风险等级：HIGH 直接阻断，MEDIUM 记录并阻断，LOW 仅告警。 */
    private final InjectionRisk risk;

    private InjectionFinding(String category, String matched, InjectionRisk risk) {
        this.category = category;
        this.matched = matched;
        this.risk = risk;
    }

    public static InjectionFinding of(String category, String matched, InjectionRisk risk) {
        return new InjectionFinding(category, matched, risk);
    }

    /** HIGH/MEDIUM 命中即应阻断推理，LOW 仅观测。 */
    public boolean shouldBlock() {
        return risk == InjectionRisk.HIGH || risk == InjectionRisk.MEDIUM;
    }

    public enum InjectionRisk {
        LOW, MEDIUM, HIGH
    }
}
