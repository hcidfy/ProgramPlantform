package com.codelab.app.common;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 JSON 工具：只用于 JWT payload 与 audit_logs.detail 这种"自己拼、自己拆"的场景。
 * 刻意不依赖 Jackson：JWT 里只有 uuid / role / 时间戳这类安全字符，手写可控且省一个依赖面。
 */
public final class JsonMini {

    private JsonMini() {
    }

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 取顶层字符串字段（值不含转义引号；本项目的 JWT 字段满足该前提）。 */
    public static Optional<String> str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** 取顶层数字字段。 */
    public static Optional<Long> num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }

    /** 拼一个扁平 JSON 对象：值只支持 String / Number / Boolean / null（够 audit_logs.detail 用）。 */
    public static String object(Map<String, ?> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append(quote(String.valueOf(v)));
            }
        }
        return sb.append('}').toString();
    }
}
