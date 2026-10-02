package com.pivotos.starter.datainspect.support;

/**
 * 结果值归一化（三个组件共用）：超长文本截断、二进制标记、数值/布尔原样透出。
 *
 * <p>存在理由：Redis 的 value 与 ES 的 {@code _source} 都可能是任意大小，
 * 不截断会直接把监控页和网关一起拖垮——<b>截断是安全红线（防拖库）的一部分，不是体验优化</b>。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public final class InspectValues {

    /** 截断标记：前端可据此提示「已截断」 */
    public static final String TRUNCATED_SUFFIX = "…(truncated)";

    private InspectValues() {
    }

    /**
     * 归一：{@code null} → {@code null}；byte[] → 长度标记；String 超长 → 截断并打标记；其余原样。
     *
     * @param maxChars 最大字符数（<=0 表示按组件默认 2048 处理）
     */
    public static Object normalize(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "<binary:" + bytes.length + " bytes>";
        }
        if (value instanceof String text) {
            return truncate(text, maxChars);
        }
        return value;
    }

    /** 字符串截断（超长打标记，便于用户知道「看到的不是全部」） */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        int limit = maxChars <= 0 ? 2048 : maxChars;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + TRUNCATED_SUFFIX;
    }

    /** 是否为截断值（供 warnings 统计用） */
    public static boolean isTruncated(Object value) {
        return value instanceof String text && text.endsWith(TRUNCATED_SUFFIX);
    }
}
