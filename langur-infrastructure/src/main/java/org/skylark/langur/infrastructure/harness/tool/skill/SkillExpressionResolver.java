package org.skylark.langur.infrastructure.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能表达式解析器（§7.5）- 负责占位符替换与条件求值，刻意保持"安全最小集"：
 * 不引入脚本引擎，仅支持 {@code ${path}} 取值与六种比较运算符，杜绝表达式注入。
 * <p>占位符路径 {@code a.b.c}：首段为上下文键，其后逐段在 Map 或 JSON 字符串内导航。</p>
 */
public class SkillExpressionResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\s*\\$\\{([^}]+)}\\s*$");
    private static final Pattern COMPARISON =
            Pattern.compile("^(.*?)(==|!=|>=|<=|>|<)(.*)$");

    private final ObjectMapper objectMapper;

    public SkillExpressionResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 递归解析参数结构中的占位符（String/Map/List），非字符串原样保留。 */
    @SuppressWarnings("unchecked")
    public Object resolveValue(Object value, Map<String, Object> ctx) {
        if (value instanceof String s) {
            Matcher whole = WHOLE_PLACEHOLDER.matcher(s);
            if (whole.matches()) {
                return lookup(whole.group(1).trim(), ctx);
            }
            return resolveString(s, ctx);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolveValue(v, ctx)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(resolveValue(item, ctx));
            }
            return out;
        }
        return value;
    }

    /** 将字符串中的所有 {@code ${path}} 替换为其字符串形式（null 视为空串）。 */
    public String resolveString(String text, Map<String, Object> ctx) {
        if (text == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object v = lookup(matcher.group(1).trim(), ctx);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 按点路径从上下文取值，支持在 Map 或 JSON 字符串内逐段导航。 */
    public Object lookup(String path, Map<String, Object> ctx) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = ctx.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            current = navigate(current, parts[i]);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Object navigate(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (node instanceof String s) {
            Object parsed = tryParseJson(s);
            if (parsed instanceof Map<?, ?> map) {
                return map.get(key);
            }
            if (parsed instanceof List<?> list) {
                try {
                    return list.get(Integer.parseInt(key));
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private Object tryParseJson(String s) {
        String t = s.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readValue(t, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 求值条件表达式：含比较运算符时按二元比较，否则对单值取真值。
     */
    public boolean evaluateCondition(String expression, Map<String, Object> ctx) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        Matcher matcher = COMPARISON.matcher(expression.trim());
        if (matcher.matches()) {
            Object left = resolveOperand(matcher.group(1), ctx);
            Object right = resolveOperand(matcher.group(3), ctx);
            return compare(left, right, matcher.group(2));
        }
        return truthy(resolveOperand(expression, ctx));
    }

    /** 解析操作数：占位符取值、引号字面量、true/false、数字，其余按含占位符的字符串处理。 */
    private Object resolveOperand(String raw, Map<String, Object> ctx) {
        String token = raw == null ? "" : raw.trim();
        Matcher whole = WHOLE_PLACEHOLDER.matcher(token);
        if (whole.matches()) {
            return lookup(whole.group(1).trim(), ctx);
        }
        if (token.length() >= 2
                && ((token.startsWith("'") && token.endsWith("'"))
                || (token.startsWith("\"") && token.endsWith("\"")))) {
            return token.substring(1, token.length() - 1);
        }
        if ("true".equalsIgnoreCase(token)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(token)) {
            return Boolean.FALSE;
        }
        if (token.isEmpty()) {
            return "";
        }
        // 可能含内嵌占位符的字符串
        String resolved = resolveString(token, ctx);
        Double number = asNumber(resolved);
        return number != null ? number : resolved;
    }

    private boolean compare(Object left, Object right, String op) {
        Double ln = asNumber(left);
        Double rn = asNumber(right);
        if (ln != null && rn != null) {
            int c = Double.compare(ln, rn);
            return switch (op) {
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case ">" -> c > 0;
                case "<" -> c < 0;
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                default -> false;
            };
        }
        String ls = left == null ? "" : String.valueOf(left);
        String rs = right == null ? "" : String.valueOf(right);
        int c = ls.compareTo(rs);
        return switch (op) {
            case "==" -> ls.equals(rs);
            case "!=" -> !ls.equals(rs);
            case ">" -> c > 0;
            case "<" -> c < 0;
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            default -> false;
        };
    }

    private boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof String s) {
            return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
        }
        return true;
    }

    private Double asNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
