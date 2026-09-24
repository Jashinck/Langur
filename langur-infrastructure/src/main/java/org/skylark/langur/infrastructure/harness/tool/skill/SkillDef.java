package org.skylark.langur.infrastructure.harness.tool.skill;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 技能定义注解（§7.5）- 标注在实现 {@link Skill} 的 Spring Bean 上，声明一个可编排的技能。
 * <p>技能以 {@code skill:{name}} 注册进 T 组件（source=SKILL），与原子工具同受四层校验链管控；
 * 技能内部的每步工具调用同样经 {@code ToolDispatcher} 派发，形成嵌套校验。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SkillDef {

    /** 技能名，参与工具 ID：skill:{name}。 */
    String name();

    String description() default "";

    /** 权限标识，供权限校验层使用。 */
    String permission() default "";

    /** 风险等级（LOW/MEDIUM/HIGH/CRITICAL）。 */
    String riskLevel() default "LOW";

    /** 技能整体超时秒数。 */
    long timeoutSeconds() default 60;
}
