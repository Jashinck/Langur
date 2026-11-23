package org.skylark.langur.infrastructure.harness.context;

import org.skylark.langur.domain.harness.context.ContextSanitizer;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * C 组件 - 示例脱敏过滤器：手机号 / 邮箱掩码为 ***。
 * <p>生产环境可扩展身份证、银行卡、地址等更多敏感模式，或对接专业脱敏服务。</p>
 */
@Component
public class KeywordMaskingContextSanitizer implements ContextSanitizer {

    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(1[3-9]\\d{9})(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final String MASK = "***";

    @Override
    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String result = PHONE.matcher(raw).replaceAll(MASK);
        return EMAIL.matcher(result).replaceAll(MASK);
    }
}
