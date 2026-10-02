package com.pivotos.starter.datainspect.security;

import com.pivotos.common.core.sensitive.SensitiveType;
import com.pivotos.starter.core.json.SensitiveMasker;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 结果集敏感列脱敏（按列名规则）。
 *
 * <p>为什么不能直接用 {@code @Sensitive} 注解：自由查询返回的是 {@code Map<String,Object>} 动态结果集，
 * 注解派的序列化期脱敏无处挂载，因此这里按列名规则显式调用 {@link SensitiveMasker}。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public final class ColumnMasker {

    /** 命中即全星：凭据类一律不展示任何片段 */
    private static final Pattern CREDENTIAL = Pattern.compile("(?i).*(password|passwd|pwd|secret|token|api_?key|private_?key|access_?key).*");

    private final Pattern pattern;

    public ColumnMasker(String maskColumnRegex) {
        String regex = (maskColumnRegex == null || maskColumnRegex.isBlank())
                ? "(?i).*(password|passwd|pwd|secret|token|api_?key|private_?key|id_?card|bank|mobile|phone|email).*"
                : maskColumnRegex;
        this.pattern = Pattern.compile(regex);
    }

    /** 该列是否命中脱敏规则 */
    public boolean isSensitive(String column) {
        return column != null && pattern.matcher(column).matches();
    }

    /** 列名或键名任一命中即脱敏（Redis 的 key 本身就是凭证，如 Authorization:sys-user:token:xxx） */
    public boolean isSensitive(String column, String keyName) {
        return isSensitive(column) || isSensitive(keyName);
    }

    /** 脱敏类型：凭据全星，手机号/邮箱/身份证/银行卡按类型部分掩码（保持可读性） */
    public SensitiveType typeOf(String column) {
        if (column == null) {
            return SensitiveType.ALL;
        }
        String lower = column.toLowerCase(Locale.ROOT);
        if (CREDENTIAL.matcher(lower).matches()) {
            return SensitiveType.ALL;
        }
        if (lower.contains("mobile") || lower.contains("phone")) {
            return SensitiveType.MOBILE;
        }
        if (lower.contains("email") || lower.contains("mail")) {
            return SensitiveType.EMAIL;
        }
        if (lower.contains("idcard") || lower.contains("id_card")) {
            return SensitiveType.ID_CARD;
        }
        if (lower.contains("bank") || lower.contains("card_no")) {
            return SensitiveType.BANK_CARD;
        }
        return SensitiveType.ALL;
    }

    /**
     * 脱敏取值：非字符串统一转字符串后按类型掩码；<b>未命中规则的列原样返回</b>。
     */
    public Object mask(String column, Object value) {
        return mask(column, value, null);
    }

    /**
     * 脱敏取值（列名或键名任一命中即脱敏）。
     *
     * <p>{@code keyName} 存在的理由：Redis 的结果集列名是固定的 {@code field/value}，但 <b>key 名本身
     * 就可能是凭证</b>（{@code Authorization:sys-user:token:xxx}）——只看列名会整片漏脱敏。
     */
    public Object mask(String column, Object value, String keyName) {
        boolean byColumn = isSensitive(column);
        boolean byKey = isSensitive(keyName);
        if (value == null || (!byColumn && !byKey)) {
            return value;
        }
        String text = value instanceof String s ? s : String.valueOf(value);
        return SensitiveMasker.mask(text, typeOf(byKey ? keyName : column));
    }
}
