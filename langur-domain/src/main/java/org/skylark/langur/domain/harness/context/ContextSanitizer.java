package org.skylark.langur.domain.harness.context;

/**
 * C 组件 - 脱敏过滤器（可插拔扩展点，支持多实现链式执行）。
 * <p>上下文装配时将记忆内容依次经过所有过滤器，防止敏感信息进入模型上下文（§9.1 输入安全）。</p>
 */
public interface ContextSanitizer {

    /**
     * 对原始内容执行脱敏，返回安全内容。
     */
    String sanitize(String raw);
}
