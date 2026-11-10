package org.skylark.langur.infrastructure.harness.tool.skill;

/**
 * 技能编排执行异常（步骤工具失败、条件跳转目标缺失、步数越界等）。
 */
public class SkillExecutionException extends RuntimeException {

    public SkillExecutionException(String message) {
        super(message);
    }

    public SkillExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
